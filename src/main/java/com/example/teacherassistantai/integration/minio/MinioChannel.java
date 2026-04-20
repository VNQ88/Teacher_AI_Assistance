package com.example.teacherassistantai.integration.minio;

import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.exception.StorageOperationException;
import com.example.teacherassistantai.integration.minio.dto.UploadResult;
import com.example.teacherassistantai.repository.SubjectRepository;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.SetBucketPolicyArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import jakarta.annotation.PostConstruct;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@EnableConfigurationProperties(MinioProps.class)
public class MinioChannel {

    private static final int MAX_PRESIGN_SECONDS = (int) Duration.ofDays(7).getSeconds(); // 604800
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "txt");

    private final MinioProps props;
    private final SubjectRepository subjectRepository;

    /**
     * Client nội bộ (trong docker network): endpoint = http://minio:9000
     * Dùng cho put/get/stat/remove...
     */
    private final MinioClient internalClient;

    /**
     * Client public để ký presigned URL: endpoint = https://minio.social.io.vn (hoặc domain public bạn dùng)
     * Dùng CHỈ cho getPresignedObjectUrl(...)
     */
    private final MinioClient presignClient;

    public MinioChannel(
            MinioProps props,
            SubjectRepository subjectRepository,
            @Qualifier("minioInternalClient") MinioClient internalClient,
            @Qualifier("minioPresignClient") MinioClient presignClient
    ) {
        this.props = props;
        this.subjectRepository = subjectRepository;
        this.internalClient = internalClient;
        this.presignClient = presignClient;
    }

    @PostConstruct
    public void init() {
        try {
            createBucketIfNeeded(props.getBucket(), props.isMakeBucketPublic());
        } catch (Exception e) {
            log.error("MinIO init failed: {}", e.getMessage(), e);
            throw new IllegalStateException("MinIO init failed", e);
        }
    }

    private void createBucketIfNeeded(final String name, boolean makePublic) throws Exception {
        boolean exists = internalClient.bucketExists(
                BucketExistsArgs.builder().bucket(name).build()
        );

        if (!exists) {
            internalClient.makeBucket(MakeBucketArgs.builder().bucket(name).build());

            if (makePublic) {
                final String policy = """
                    {
                      "Version": "2012-10-17",
                      "Statement": [{
                        "Effect": "Allow",
                        "Principal": "*",
                        "Action": "s3:GetObject",
                        "Resource": "arn:aws:s3:::%s/*"
                      }]
                    }
                    """.formatted(name);

                internalClient.setBucketPolicy(
                        SetBucketPolicyArgs.builder().bucket(name).config(policy).build()
                );
                log.warn("Bucket {} đã được đặt PUBLIC (GET). Cân nhắc để private cho an toàn.", name);
            } else {
                log.info("Bucket {} được tạo (private).", name);
            }
        } else {
            log.info("Bucket {} đã tồn tại.", name);
        }
    }

    /** Suy luận MIME type chuẩn từ tên file; fallback octet-stream. */
    private String detectContentType(String filename, String fallback) {
        try {
            String guess = URLConnection.guessContentTypeFromName(filename);
            if (StringUtils.hasText(guess)) return guess;
        } catch (Exception ignored) {}
        return fallback;
    }

    /** Tạo object key duy nhất: {prefix/}{uuid}-{ten-goc} */
    private String buildObjectKey(String originalName) {
        String clean = (originalName == null || originalName.isBlank())
                ? "file.bin"
                : originalName.strip().replace("\\", "/");
        String nameOnly = clean.substring(clean.lastIndexOf('/') + 1);

        String prefix = props.getKeyPrefix() == null ? "" : props.getKeyPrefix().trim();
        if (!prefix.isEmpty() && !prefix.endsWith("/")) prefix += "/";

        return prefix + UUID.randomUUID() + "-" + nameOnly;
    }

    private static int normalizeExpirySeconds(int ttlSeconds, int fallbackSeconds) {
        int s = ttlSeconds > 0 ? ttlSeconds : fallbackSeconds;
        if (s > MAX_PRESIGN_SECONDS) s = MAX_PRESIGN_SECONDS;
        return Math.max(s, 1);
    }

    /** Upload file và trả về pre-signed GET URL với TTL cấu hình. */
    public UploadResult upload(@NonNull final MultipartFile file) throws Exception {
        final String objectKey = buildObjectKey(file.getOriginalFilename());
        return upload(file, objectKey);
    }

    /** Upload theo subject: validate file + validate subject + tạo key theo subject folder. */
    public UploadResult upload(@NonNull final MultipartFile file, @NonNull final Long subjectId) throws Exception {
        validateUploadFile(file);
        if (subjectId <= 0) {
            throw new IllegalArgumentException("subjectId must be greater than 0");
        }

        Subject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new ResourceNotFoundException("Subject not found with id: " + subjectId));

        final String extension = getFileExtension(file.getOriginalFilename());
        final String objectKey = buildSubjectObjectKey(subject, extension);
        return upload(file, objectKey);
    }

    /** Upload file với object key được chỉ định trước (dùng cho folder theo subject/classroom). */
    public UploadResult upload(@NonNull final MultipartFile file, @NonNull final String objectKey) throws Exception {
        final String contentType = Objects.requireNonNullElse(
                file.getContentType(),
                detectContentType(file.getOriginalFilename(), "application/octet-stream")
        );

        try (InputStream in = file.getInputStream()) {
            internalClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(props.getBucket())
                            .object(objectKey)
                            .contentType(contentType)
                            .stream(in, file.getSize(), -1)
                            .build()
            );
        }

        // Presign bằng presignClient (endpoint PUBLIC) => KHÔNG replace host nữa
        String url = presignClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(props.getBucket())
                        .object(objectKey)
                        .expiry(normalizeExpirySeconds(0, props.getPresignExpirySeconds()))
                        .build()
        );

        return new UploadResult(objectKey, url);
    }

    private void validateUploadFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }
        String extension = getFileExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Only pdf/docx/txt files are allowed");
        }
    }

    private String getFileExtension(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            throw new IllegalArgumentException("Invalid file name");
        }
        String ext = StringUtils.getFilenameExtension(originalFilename);
        if (!StringUtils.hasText(ext)) {
            throw new IllegalArgumentException("File extension is required");
        }
        return ext.toLowerCase();
    }

    private String buildSubjectObjectKey(Subject subject, String extension) {
        String safeSubjectCode = sanitizePathSegment(subject.getCode());
        String uuid = UUID.randomUUID().toString();
        return String.format("uploads/subjects/%s/%s.%s", safeSubjectCode, uuid, extension);
    }

    private String sanitizePathSegment(String value) {
        if (!StringUtils.hasText(value)) {
            return "unknown";
        }
        String normalized = value.trim().toLowerCase().replaceAll("[^a-z0-9-_]", "-");
        return normalized.replaceAll("-+", "-");
    }

    public GetObjectResponse downloadStream(String bucket, String objectKey) throws Exception {
        return internalClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .build()
        );
    }

    /** (Tuỳ chọn) Tạo pre-signed URL GET/PUT với TTL truyền vào. */
    public String presignedGetUrl(String objectKey, int ttlSeconds) throws Exception {
        return presignClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(props.getBucket())
                        .object(objectKey)
                        .expiry(normalizeExpirySeconds(ttlSeconds, props.getPresignExpirySeconds()))
                        .build()
        );
    }

    public String presignedPutUrl(String objectKey, int ttlSeconds, String contentType) throws Exception {
        // Giữ nguyên signature và tham số contentType như code cũ.
        // Lưu ý: nếu bạn muốn server bắt buộc Content-Type khớp khi upload,
        // cách đúng là ký với signed headers; ở đây giữ logic cũ (extraQueryParams) để không phá call-site.
        return presignClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.PUT)
                        .bucket(props.getBucket())
                        .object(objectKey)
                        .expiry(normalizeExpirySeconds(ttlSeconds, props.getPresignExpirySeconds()))
                        .extraQueryParams(
                                StringUtils.hasText(contentType)
                                        ? java.util.Map.of("Content-Type", contentType)
                                        : java.util.Collections.emptyMap()
                        )
                        .build()
        );
    }

    public StatObjectResponse statObject(String bucket, String objectKey) throws Exception {
        return internalClient.statObject(
                StatObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .build()
        );
    }

    public GetObjectResponse getObjectRange(String bucket, String objectKey, long start, long length) throws Exception {
        return internalClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .offset(start)
                        .length(length) // -1 = tới EOF
                        .build()
        );
    }

    public void removeObject(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return;
        try {
            internalClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(props.getBucket())
                            .object(objectKey)
                            .build()
            );
            log.info("Removed object {} from bucket {}", objectKey, props.getBucket());
        } catch (Exception e) {
            log.error("Failed to remove object {} from bucket {}", objectKey, props.getBucket(), e);
            throw new StorageOperationException("Could not remove object from MinIO", e);
        }
    }

    public void removeObjects(List<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) return;

        try {
            List<DeleteObject> deletes = objectKeys.stream()
                    .filter(Objects::nonNull)
                    .filter(s -> !s.isBlank())
                    .map(DeleteObject::new)
                    .toList();

            if (!deletes.isEmpty()) {
                Iterable<Result<DeleteError>> results = internalClient.removeObjects(
                        RemoveObjectsArgs.builder()
                                .bucket(props.getBucket())
                                .objects(deletes)
                                .build()
                );

                for (Result<DeleteError> result : results) {
                    try {
                        DeleteError error = result.get();
                        if (error != null) {
                            log.warn("Failed to delete object {} - {}", error.objectName(), error.message());
                        }
                    } catch (ErrorResponseException e) {
                        log.warn("Delete error response: {}", e.getMessage());
                        throw e;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error deleting objects in MinIO", e);
        }
    }

    /** Đọc toàn bộ InputStream → byte[] với buffer 8KB (giữ lại nếu bạn cần). */
    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }
}


