package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.SubjectType;
import com.example.teacherassistantai.dto.request.CreateSubjectRequest;
import com.example.teacherassistantai.dto.request.UpdateSubjectRequest;
import com.example.teacherassistantai.dto.response.SubjectResponse;
import com.example.teacherassistantai.entity.Role;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.exception.AccessDeniedOperationException;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.exception.StorageOperationException;
import com.example.teacherassistantai.integration.minio.MinioChannel;
import com.example.teacherassistantai.repository.AgentLogRepository;
import com.example.teacherassistantai.repository.ChatMessageRepository;
import com.example.teacherassistantai.repository.ChatSessionRepository;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import com.example.teacherassistantai.repository.DocumentRepository.DocumentStorageObject;
import com.example.teacherassistantai.repository.SubjectRepository;
import com.example.teacherassistantai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;


@Service
@RequiredArgsConstructor
public class SubjectService {

    private final SubjectRepository subjectRepository;
    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final DocumentNodeRepository documentNodeRepository;
    private final DocumentNodeArtifactRepository documentNodeArtifactRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final AgentLogRepository agentLogRepository;
    private final MinioChannel minioChannel;

    @Transactional(readOnly = true)
    public List<SubjectResponse> getAllSubjects() {
        return subjectRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public SubjectResponse getSubjectById(Long subjectId) {
        Subject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new ResourceNotFoundException("Subject not found with id: " + subjectId));
        return toResponse(subject);
    }

    @Transactional
    public SubjectResponse createSubject(CreateSubjectRequest request) {
        if (subjectRepository.existsByNameIgnoreCase(request.getName())) {
            throw new InvalidDataException("Subject name already exists");
        }
        if (subjectRepository.existsByCodeIgnoreCase(request.getCode())) {
            throw new InvalidDataException("Subject code already exists");
        }
        SubjectType subjectType = request.getSubjectType();
        if  (subjectType == null)
            subjectType = SubjectType.TEXT_BASED;

        User currentUser = getCurrentUser();
        Subject subject = Subject.builder()
                .ownerId(currentUser.getId())
                .name(request.getName().trim())
                .code(request.getCode().trim().toUpperCase())
                .description(request.getDescription())
                .subjectType(subjectType)
                .active(true)
                .build();

        return toResponse(subjectRepository.save(subject));
    }

    @Transactional
    public SubjectResponse updateSubject(Long subjectId, UpdateSubjectRequest request) {
        Subject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new ResourceNotFoundException("Subject not found with id: " + subjectId));

        validateSubjectOwnership(subject, getCurrentUser());

        subject.setName(request.getName().trim());
        subject.setDescription(request.getDescription());
        subject.setActive(request.getActive());

        SubjectType subjectType = request.getSubjectType();
        if (subjectType == null)
            subjectType = SubjectType.TEXT_BASED;
        subject.setSubjectType(subjectType);
        return toResponse(subjectRepository.save(subject));
    }

    @Transactional
    public void deleteSubject(Long subjectId) {
        Subject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new ResourceNotFoundException("Subject not found with id: " + subjectId));

        validateSubjectOwnership(subject, getCurrentUser());

        List<DocumentStorageObject> storageObjects = documentRepository.findStorageObjectsBySubjectId(subjectId);
        storageObjects.forEach(this::removeDocumentStorageObjects);

        agentLogRepository.deleteBySubjectId(subjectId);

        chatMessageRepository.deleteMessageSourceLinksBySubjectId(subjectId);
        chatMessageRepository.deleteBySubjectId(subjectId);
        chatSessionRepository.deleteBySubjectId(subjectId);

        documentChunkRepository.deleteMessageSourceLinksBySubjectId(subjectId);
        documentNodeArtifactRepository.deleteBySubjectId(subjectId);
        documentChunkRepository.deleteBySubjectId(subjectId);
        documentNodeRepository.deleteBySubjectId(subjectId);
        documentRepository.deleteBySubjectId(subjectId);

        subjectRepository.delete(subject);
    }

    public void validateSubjectOwnershipById(Long subjectId) {
        Subject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new ResourceNotFoundException("Subject not found with id: " + subjectId));
        validateSubjectOwnership(subject, getCurrentUser());
    }

    private void validateSubjectOwnership(Subject subject, User currentUser) {
        boolean isAdmin = currentUser.getRoles().stream()
                .map(Role::getName)
                .anyMatch("ADMIN"::equalsIgnoreCase);
        boolean isOwner = subject.getOwnerId() != null
                && subject.getOwnerId().equals(currentUser.getId());
        if (!isAdmin && !isOwner) {
            throw new AccessDeniedOperationException("You don't have permission to modify this subject");
        }
    }

    private User getCurrentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ResourceNotFoundException("User not authenticated");
        }
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private void removeDocumentStorageObjects(DocumentStorageObject document) {
        removeStorageObject(document.getOriginalObjectKey(), document.getId(), "original");
        removeStorageObject(document.getMarkdownObjectKey(), document.getId(), "markdown");
        removeStorageObject(document.getHierarchyObjectKey(), document.getId(), "hierarchy");
        removeStorageObject(document.getChunksObjectKey(), document.getId(), "chunks");
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

    private SubjectResponse toResponse(Subject subject) {
        return SubjectResponse.builder()
                .id(subject.getId())
                .name(subject.getName())
                .code(subject.getCode())
                .description(subject.getDescription())
                .subjectType(subject.getSubjectType())
                .active(subject.getActive())
                .build();
    }
}
