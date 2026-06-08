package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import com.example.teacherassistantai.common.enumerate.DocumentEnrichmentStatus;
import com.example.teacherassistantai.common.response.PageResponse;
import com.example.teacherassistantai.config.DocumentIngestionProps;
import com.example.teacherassistantai.dto.request.UpdateDocumentRequest;
import com.example.teacherassistantai.dto.response.DocumentResponse;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.Role;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.exception.AccessDeniedOperationException;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.exception.StorageOperationException;
import com.example.teacherassistantai.integration.minio.MinioChannel;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import com.example.teacherassistantai.repository.SubjectRepository;
import com.example.teacherassistantai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private static final List<String> ALLOWED_EXTENSIONS = List.of("pdf", "docx", "txt");
    private static final String SUBJECT_DOCUMENT_EXISTS_MESSAGE =
            "Môn học này đã có tài liệu. Vui lòng xóa tài liệu hiện tại trước khi tải lên tài liệu mới.";

    private final DocumentRepository documentRepository;
    private final SubjectRepository subjectRepository;
    private final UserRepository userRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final MinioChannel minioChannel;
    private final DocumentProcessingService documentProcessingService;
    private final DocumentIngestionProps ingestionProps;

    @Transactional
    public DocumentResponse uploadDocument(MultipartFile file,
                                           Long subjectId,
                                           String title,
                                           String description) {
        validateUploadRequest(file);

        Subject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy môn học với id: " + subjectId));

        if (documentRepository.existsBySubjectId(subject.getId())) {
            throw new InvalidDataException(SUBJECT_DOCUMENT_EXISTS_MESSAGE);
        }

        User currentUser = getCurrentUser();
        String extension = getExtension(file.getOriginalFilename());
        String normalizedType = extension.toUpperCase(Locale.ROOT);

        validateFileSizeByType(file, extension);

        String objectKey = buildOriginalObjectKey(subject.getId(), file.getOriginalFilename());
        try {
            minioChannel.upload(file, objectKey);
        } catch (Exception e) {
            throw new StorageOperationException("Không thể tải tài liệu lên kho lưu trữ. Vui lòng thử lại.", e);
        }

        String resolvedTitle = StringUtils.hasText(title)
                ? title.trim()
                : getBaseName(file.getOriginalFilename());

        Document document = Document.builder()
                .title(resolvedTitle)
                .description(description)
                .originalObjectKey(objectKey)
                .markdownObjectKey(null)
                .fileType(normalizedType)
                .fileSizeBytes(file.getSize())
                .subject(subject)
                .uploadedBy(currentUser)
                .status(DocumentStatus.UPLOADED)
                .build();

        Document saved;
        try {
            saved = documentRepository.saveAndFlush(document);
        } catch (DataIntegrityViolationException ex) {
            cleanupUploadedObjectAfterSaveFailure(objectKey);
            throw new InvalidDataException(SUBJECT_DOCUMENT_EXISTS_MESSAGE);
        }
        log.info("Uploaded document id={}, subjectId={}, type={}", saved.getId(), subjectId, normalizedType);

        triggerProcessingAfterCommit(saved.getId());
        return toResponse(saved);
    }

    private void triggerProcessingAfterCommit(Long documentId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    documentProcessingService.processDocumentAsync(documentId);
                }
            });
            return;
        }

        // Fallback for non-transactional callers.
        documentProcessingService.processDocumentAsync(documentId);
    }

    @Transactional(readOnly = true)
    public DocumentResponse getDocumentById(Long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài liệu với id: " + documentId));
        return toResponse(document);
    }

    @Transactional(readOnly = true)
    public PageResponse<?> getDocuments(Long subjectId, DocumentStatus status, int pageNo, int pageSize) {
        Page<Document> page = documentRepository.findByFilters(subjectId, status, PageRequest.of(pageNo, pageSize));
        List<DocumentResponse> items = page.getContent().stream().map(this::toResponse).toList();

        return PageResponse.<List<DocumentResponse>>builder()
                .pageNo(pageNo)
                .pageSize(pageSize)
                .totalPage(page.getTotalPages())
                .items(items)
                .build();
    }

    @Transactional
    public void deleteDocument(Long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài liệu với id: " + documentId));

        User currentUser = getCurrentUser();
        validateDeletePermission(document, currentUser);

        removeStorageObject(document.getOriginalObjectKey(), documentId, "gốc");
        removeStorageObject(document.getMarkdownObjectKey(), documentId, "markdown");
        removeStorageObject(document.getHierarchyObjectKey(), documentId, "cấu trúc");
        removeStorageObject(document.getChunksObjectKey(), documentId, "danh sách đoạn");

        documentChunkRepository.deleteMessageSourceLinksByDocumentId(documentId);
        documentChunkRepository.deleteByDocumentId(documentId);
        documentRepository.delete(document);
        log.info("Deleted document id={} and related chunks", documentId);
    }

    @Transactional
    public void deleteDocumentById(Long documentId) {
        deleteDocument(documentId);
    }

    @Transactional
    public DocumentResponse updateDocument(Long documentId, UpdateDocumentRequest request) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài liệu với id: " + documentId));

        User currentUser = getCurrentUser();
        validateDeletePermission(document, currentUser);

        document.setTitle(request.getTitle().trim());
        document.setDescription(request.getDescription());
        Document saved = documentRepository.save(document);
        log.info("Updated document id={}", documentId);
        return toResponse(saved);
    }

    @Transactional
    public DocumentResponse reprocessDocument(Long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài liệu với id: " + documentId));

        User currentUser = getCurrentUser();
        validateDeletePermission(document, currentUser);

        document.setStatus(DocumentStatus.UPLOADED);
        document.setProcessingError(null);
        document.setEnrichmentStatus(DocumentEnrichmentStatus.NOT_STARTED);
        document.setEnrichmentError(null);
        Document saved = documentRepository.save(document);
        log.info("Reprocessing document id={}", documentId);

        triggerProcessingAfterCommit(documentId);
        return toResponse(saved);
    }

    private void validateUploadRequest(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidDataException("Vui lòng chọn file tài liệu để tải lên.");
        }

        String extension = getExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new InvalidDataException("Chỉ hỗ trợ file PDF, DOCX hoặc TXT.");
        }
    }

    private void validateFileSizeByType(MultipartFile file, String extension) {
        if ("docx".equals(extension) && file.getSize() >= ingestionProps.getMaxDocxBytes()) {
            throw new InvalidDataException("Dung lượng file DOCX phải nhỏ hơn 100KB.");
        }
        if ("txt".equals(extension) && file.getSize() >= ingestionProps.getMaxTxtBytes()) {
            throw new InvalidDataException("Dung lượng file TXT phải nhỏ hơn 100KB.");
        }
    }

    private String buildOriginalObjectKey(Long subjectId, String originalFilename) {
        String safeName = sanitizeFileName(originalFilename);
        return String.format("uploads/subjects/%d/original/%s-%s", subjectId, UUID.randomUUID(), safeName);
    }

    private String sanitizeFileName(String originalFilename) {
        String fallback = "document.bin";
        if (!StringUtils.hasText(originalFilename)) return fallback;
        return originalFilename.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String getExtension(String originalFilename) {
        String ext = StringUtils.getFilenameExtension(originalFilename);
        if (!StringUtils.hasText(ext)) {
            throw new InvalidDataException("Tên file phải có phần mở rộng PDF, DOCX hoặc TXT.");
        }
        return ext.toLowerCase(Locale.ROOT);
    }

    private String getBaseName(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) return "Untitled document";
        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex <= 0) return originalFilename;
        return originalFilename.substring(0, dotIndex);
    }

    private User getCurrentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ResourceNotFoundException("Bạn cần đăng nhập để thực hiện thao tác này.");
        }
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thông tin người dùng hiện tại."));
    }

    private void validateDeletePermission(Document document, User currentUser) {
        boolean isAdmin = currentUser.getRoles().stream()
                .map(Role::getName)
                .anyMatch("ADMIN"::equalsIgnoreCase);

        Long uploaderId = document.getUploadedBy() == null ? null : document.getUploadedBy().getId();
        boolean isUploader = uploaderId != null && uploaderId.equals(currentUser.getId());

        if (!isAdmin && !isUploader) {
            throw new AccessDeniedOperationException("Bạn không có quyền thao tác với tài liệu này.");
        }
    }

    private void removeStorageObject(String objectKey, Long documentId, String objectType) {
        if (!StringUtils.hasText(objectKey)) {
            return;
        }

        try {
            minioChannel.removeObject(objectKey);
        } catch (StorageOperationException ex) {
            throw new StorageOperationException(
                    "Không thể xóa file %s của tài liệu id=%d khỏi kho lưu trữ.".formatted(objectType, documentId),
                    ex
            );
        }
    }

    private void cleanupUploadedObjectAfterSaveFailure(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return;
        }
        try {
            minioChannel.removeObject(objectKey);
        } catch (StorageOperationException ex) {
            log.warn("Failed to cleanup uploaded object after document save failure: objectKey={}", objectKey, ex);
        }
    }

    private DocumentResponse toResponse(Document document) {
        return DocumentResponse.builder()
                .id(document.getId())
                .title(document.getTitle())
                .description(document.getDescription())
                .subjectId(document.getSubject().getId())
                .subjectName(document.getSubject().getName())
                .fileType(document.getFileType())
                .fileSizeBytes(document.getFileSizeBytes())
                .originalObjectKey(document.getOriginalObjectKey())
                .markdownObjectKey(document.getMarkdownObjectKey())
                .hierarchyObjectKey(document.getHierarchyObjectKey())
                .chunksObjectKey(document.getChunksObjectKey())
                .status(document.getStatus())
                .statusLabel(statusLabel(document.getStatus()))
                .enrichmentStatus(document.getEnrichmentStatus())
                .enrichmentStatusLabel(enrichmentStatusLabel(document.getEnrichmentStatus()))
                .ragReady(isRagReady(document.getStatus()))
                .learningMaterialsReady(document.getStatus() == DocumentStatus.READY
                        && document.getEnrichmentStatus() == DocumentEnrichmentStatus.ENRICHED)
                .processingError(document.getProcessingError())
                .enrichmentError(document.getEnrichmentError())
                .enrichmentStartedAt(document.getEnrichmentStartedAt())
                .enrichmentCompletedAt(document.getEnrichmentCompletedAt())
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .build();
    }

    private boolean isRagReady(DocumentStatus status) {
        return status == DocumentStatus.READY;
    }

    private String statusLabel(DocumentStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case UPLOADED -> "Đã tải lên";
            case PARSING -> "Đang đọc";
            case CHUNKING -> "Đang chia đoạn";
            case EMBEDDING -> "Đang lập chỉ mục";
            case SUMMARISING -> "Đang tạo học liệu";
            case READY -> "Sẵn sàng học tập";
            case FAILED -> "Lỗi xử lý";
        };
    }

    private String enrichmentStatusLabel(DocumentEnrichmentStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case NOT_STARTED -> "Chưa tạo học liệu";
            case QUEUED -> "Đang chờ";
            case RUNNING -> "Đang tạo học liệu";
            case ENRICHED -> "Đủ học liệu";
            case PARTIAL_FAILED -> "Thiếu một phần";
            case FAILED -> "Lỗi học liệu";
            case SKIPPED -> "Đã bỏ qua";
        };
    }
}
