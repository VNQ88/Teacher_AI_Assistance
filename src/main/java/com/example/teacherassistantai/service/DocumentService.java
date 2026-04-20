package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import com.example.teacherassistantai.common.response.PageResponse;
import com.example.teacherassistantai.config.DocumentIngestionProps;
import com.example.teacherassistantai.dto.response.DocumentResponse;
import com.example.teacherassistantai.entity.Classroom;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.Role;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.exception.AccessDeniedOperationException;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.exception.StorageOperationException;
import com.example.teacherassistantai.integration.minio.MinioChannel;
import com.example.teacherassistantai.repository.ClassroomRepository;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import com.example.teacherassistantai.repository.SubjectRepository;
import com.example.teacherassistantai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.lang.Nullable;
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

    private final DocumentRepository documentRepository;
    private final SubjectRepository subjectRepository;
    private final ClassroomRepository classroomRepository;
    private final UserRepository userRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final MinioChannel minioChannel;
    private final DocumentProcessingService documentProcessingService;
    private final DocumentIngestionProps ingestionProps;

    @Transactional
    public DocumentResponse uploadDocument(MultipartFile file,
                                           Long subjectId,
                                           @Nullable  Long classroomId,
                                           String title,
                                           String description) {
        validateUploadRequest(file);

        Subject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new ResourceNotFoundException("Subject not found with id: " + subjectId));

        Classroom classroom = null;
        if (classroomId != null) {
            classroom = classroomRepository.findById(classroomId)
                    .orElseThrow(() -> new ResourceNotFoundException("Classroom not found with id: " + classroomId));
            if (!classroom.getSubject().getId().equals(subjectId)) {
                throw new InvalidDataException("Classroom does not belong to provided subject");
            }
        }

        User currentUser = getCurrentUser();
        String extension = getExtension(file.getOriginalFilename());
        String normalizedType = extension.toUpperCase(Locale.ROOT);

        validateFileSizeByType(file, extension);

        String objectKey = buildOriginalObjectKey(subject.getId(), file.getOriginalFilename());
        try {
            minioChannel.upload(file, objectKey);
        } catch (Exception e) {
            throw new StorageOperationException("Upload original document to storage failed", e);
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
                .classroom(classroom)
                .uploadedBy(currentUser)
                .status(DocumentStatus.UPLOADED)
                .build();

        Document saved = documentRepository.save(document);
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
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with id: " + documentId));
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
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with id: " + documentId));

        User currentUser = getCurrentUser();
        validateDeletePermission(document, currentUser);

        removeStorageObject(document.getOriginalObjectKey(), documentId, "original");
        removeStorageObject(document.getMarkdownObjectKey(), documentId, "markdown");

        documentChunkRepository.deleteMessageSourceLinksByDocumentId(documentId);
        documentChunkRepository.deleteByDocumentId(documentId);
        documentRepository.delete(document);
        log.info("Deleted document id={} and related chunks", documentId);
    }

    @Transactional
    public void deleteDocumentById(Long documentId) {
        deleteDocument(documentId);
    }

    private void validateUploadRequest(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidDataException("File is required");
        }

        String extension = getExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new InvalidDataException("Only PDF/DOCX/TXT are supported");
        }
    }

    private void validateFileSizeByType(MultipartFile file, String extension) {
        if ("docx".equals(extension) && file.getSize() >= ingestionProps.getMaxDocxBytes()) {
            throw new InvalidDataException("DOCX file must be smaller than 100KB");
        }
        if ("txt".equals(extension) && file.getSize() >= ingestionProps.getMaxTxtBytes()) {
            throw new InvalidDataException("TXT file must be smaller than 100KB");
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
            throw new InvalidDataException("File extension is required");
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
            throw new ResourceNotFoundException("User not authenticated");
        }
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private void validateDeletePermission(Document document, User currentUser) {
        boolean isAdmin = currentUser.getRoles().stream()
                .map(Role::getName)
                .anyMatch("ADMIN"::equalsIgnoreCase);

        Long uploaderId = document.getUploadedBy() == null ? null : document.getUploadedBy().getId();
        boolean isUploader = uploaderId != null && uploaderId.equals(currentUser.getId());

        if (!isAdmin && !isUploader) {
            throw new AccessDeniedOperationException("You don't have permission to delete this document");
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
                    "Failed to delete %s object for document id=%d".formatted(objectType, documentId),
                    ex
            );
        }
    }

    private DocumentResponse toResponse(Document document) {
        return DocumentResponse.builder()
                .id(document.getId())
                .title(document.getTitle())
                .description(document.getDescription())
                .subjectId(document.getSubject().getId())
                .subjectName(document.getSubject().getName())
                .classroomId(document.getClassroom() != null ? document.getClassroom().getId() : null)
                .classroomName(document.getClassroom() != null ? document.getClassroom().getName() : null)
                .fileType(document.getFileType())
                .fileSizeBytes(document.getFileSizeBytes())
                .originalObjectKey(document.getOriginalObjectKey())
                .markdownObjectKey(document.getMarkdownObjectKey())
                .status(document.getStatus())
                .processingError(document.getProcessingError())
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .build();
    }
}
