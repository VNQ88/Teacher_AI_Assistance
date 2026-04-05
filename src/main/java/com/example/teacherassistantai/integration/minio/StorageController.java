package com.example.teacherassistantai.integration.minio;


import com.example.teacherassistantai.common.response.ResponseData;
import com.example.teacherassistantai.integration.minio.dto.PresignPutRequest;
import com.example.teacherassistantai.integration.minio.dto.UploadResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/storage")
@RequiredArgsConstructor
@Validated
@Tag(name = "Storage Controller")
public class StorageController {

    private final MinioChannel minioChannel;
    private final MinioProps minioProps;

    /* ------------ 1) Upload trực tiếp qua backend (đơn giản) ------------ */
    @Operation(summary = "Upload file", description = "Upload file directly via backend (simple, but not recommended for large files)")
    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestPart("file") MultipartFile file,
                                    @RequestParam("subjectId") Long subjectId) throws Exception {
        UploadResult result = minioChannel.upload(file, subjectId);

        return ResponseEntity.ok(Map.of(
                "objectKey", result.objectKey(),
                "url", result.url(),
                "ttlSeconds", minioProps.getPresignExpirySeconds()
        ));
    }

    /* ------------ 2) Presign PUT: Lấy link upload tạm ------------ */
    @Operation(summary = "Presign PUT URL", description = "Get a presigned PUT URL for direct upload to MinIO/S3")
    @PostMapping("/put")
    public ResponseData<?> presignPut(@Valid @RequestBody PresignPutRequest req) throws Exception {
        String ext = getExtension(req.getContentType());
        String objectKey;

        Authentication authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        String username = (authentication != null && authentication.getName() != null)
                ? authentication.getName()
                : "anonymous";

        switch (req.getResourceType()) {
            case AVATAR ->
                // userId bạn có thể lấy từ principal thay vì username
                    objectKey = String.format("avatars/%s/avatar.%s", username, ext);
            case LESSON -> {
                if (req.getCourseId() == null) {
                    throw new IllegalArgumentException("courseId is required for LESSON resource type");
                }
                String uuid = UUID.randomUUID().toString();
                objectKey = String.format("uploads/courses/%d/%s.%s", req.getCourseId(), uuid, ext);
            }
            default -> {
                String uuid = UUID.randomUUID().toString();
                objectKey = String.format("uploads/other/%s.%s", uuid, ext);
            }
        }

        String uploadUrl = minioChannel.presignedPutUrl(objectKey, req.getTtlSeconds(), req.getContentType());

        return new ResponseData<>(HttpStatus.OK.value(), "Presign success",
                new UploadResult(objectKey, uploadUrl));
    }

    /** Helper để lấy đuôi file từ contentType */
    private String getExtension(String contentType) {
        if (contentType == null) return "dat";
        return switch (contentType) {
            case "video/mp4" -> "mp4";
            case "application/pdf" -> "pdf";
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            default -> "dat";
        };
    }

    /* ------------ 3) Presign GET: Lấy link tải tạm ------------ */
    @GetMapping("/presign/get")
    @Operation(summary = "Presign GET URL", description = "Get a presigned GET URL for using to load/use file from MinIO/S3")
    public ResponseEntity<?> presignGet(@RequestParam("key") String objectKey,
                                        @RequestParam(value = "ttl", required = false) Integer ttlSeconds) throws Exception {
        int ttl = (ttlSeconds != null && ttlSeconds > 0) ? ttlSeconds : minioProps.getPresignExpirySeconds();
        String url = minioChannel.presignedGetUrl(objectKey, ttl);
        return ResponseEntity.ok(Map.of(
                "url", url,
                "ttlSeconds", ttl
        ));
    }


}
