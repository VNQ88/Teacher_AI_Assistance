package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentEnrichmentStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import com.example.teacherassistantai.dto.request.DocumentEnrichmentRequest;
import com.example.teacherassistantai.dto.response.DocumentEnrichmentJobResponse;
import com.example.teacherassistantai.dto.response.DocumentNodeArtifactResponse;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DocumentEnrichmentAdminService {

    private final DocumentRepository documentRepository;
    private final DocumentNodeRepository documentNodeRepository;
    private final DocumentNodeArtifactRepository artifactRepository;
    private final DocumentEnrichmentService enrichmentService;

    @Transactional(readOnly = true)
    public List<DocumentNodeArtifactResponse> getDocumentArtifacts(Long documentId) {
        ensureDocumentExists(documentId);
        return artifactRepository.findByDocumentIdOrderByNodeOrderAndArtifactType(documentId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DocumentNodeArtifactResponse> getNodeArtifacts(Long documentId, Long nodeId) {
        DocumentNode node = getNodeInDocument(documentId, nodeId);
        return artifactRepository.findByDocumentNodeIdOrderByArtifactTypeAscUpdatedAtDesc(node.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public DocumentEnrichmentJobResponse enrichDocument(Long documentId, DocumentEnrichmentRequest request) {
        Document document = markQueued(documentId, forceRegenerate(request));
        List<DocumentNodeArtifactType> artifactTypes = artifactTypes(request);
        enqueueAfterCommit(() -> enrichmentService.enqueueDocumentEnrichment(documentId, forceRegenerate(request), artifactTypes));
        return jobResponse(document, null, artifactTypes, forceRegenerate(request), "Document enrichment queued");
    }

    @Transactional
    public DocumentEnrichmentJobResponse enrichNode(Long documentId, Long nodeId, DocumentEnrichmentRequest request) {
        DocumentNode node = getNodeInDocument(documentId, nodeId);
        Document document = markQueued(documentId, forceRegenerate(request));
        List<DocumentNodeArtifactType> artifactTypes = artifactTypes(request);
        enqueueAfterCommit(() -> enrichmentService.enqueueNodeEnrichment(node.getId(), forceRegenerate(request), artifactTypes));
        return jobResponse(document, node.getId(), artifactTypes, forceRegenerate(request), "Node enrichment queued");
    }

    @Transactional
    public DocumentEnrichmentJobResponse retryFailedArtifacts(Long documentId, DocumentEnrichmentRequest request) {
        Document document = markQueued(documentId, false);
        List<DocumentNodeArtifactType> artifactTypes = artifactTypes(request);
        enqueueAfterCommit(() -> enrichmentService.enqueueDocumentEnrichment(documentId, false, artifactTypes));
        return jobResponse(document, null, artifactTypes, false, "Failed artifact retry queued");
    }

    @Transactional
    public void deleteArtifacts(Long documentId, DocumentEnrichmentRequest request) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with id: " + documentId));
        List<DocumentNodeArtifactType> artifactTypes = artifactTypes(request);
        if (artifactTypes.isEmpty()) {
            artifactRepository.deleteByDocumentId(documentId);
        } else {
            artifactRepository.deleteByDocumentIdAndArtifactTypeIn(documentId, artifactTypes);
        }
        if (document.getStatus() == DocumentStatus.FULL_USE) {
            document.setStatus(DocumentStatus.READY);
        }
        document.setEnrichmentStatus(DocumentEnrichmentStatus.NOT_STARTED);
        document.setEnrichmentStartedAt(null);
        document.setEnrichmentCompletedAt(null);
        document.setEnrichmentError(null);
        documentRepository.save(document);
    }

    private Document markQueued(Long documentId, boolean forceRegenerate) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with id: " + documentId));
        if (document.getStatus() != DocumentStatus.READY && document.getStatus() != DocumentStatus.FULL_USE) {
            throw new InvalidDataException("Document must be READY or FULL_USE before enrichment can run");
        }
        if (forceRegenerate && document.getStatus() == DocumentStatus.FULL_USE) {
            document.setStatus(DocumentStatus.READY);
        }
        document.setEnrichmentStatus(DocumentEnrichmentStatus.QUEUED);
        document.setEnrichmentCompletedAt(null);
        document.setEnrichmentError(null);
        return documentRepository.save(document);
    }

    private Document ensureDocumentExists(Long documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with id: " + documentId));
    }

    private DocumentNode getNodeInDocument(Long documentId, Long nodeId) {
        DocumentNode node = documentNodeRepository.findById(nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Document node not found with id: " + nodeId));
        Long nodeDocumentId = node.getDocument() == null ? null : node.getDocument().getId();
        if (!documentId.equals(nodeDocumentId)) {
            throw new InvalidDataException("Document node does not belong to document id: " + documentId);
        }
        return node;
    }

    private List<DocumentNodeArtifactType> artifactTypes(DocumentEnrichmentRequest request) {
        if (request == null || request.getArtifactTypes() == null) {
            return List.of();
        }
        return request.getArtifactTypes().stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    private boolean forceRegenerate(DocumentEnrichmentRequest request) {
        return request != null && Boolean.TRUE.equals(request.getForceRegenerate());
    }

    private void enqueueAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
            return;
        }
        task.run();
    }

    private DocumentEnrichmentJobResponse jobResponse(Document document,
                                                      Long nodeId,
                                                      List<DocumentNodeArtifactType> artifactTypes,
                                                      boolean forceRegenerate,
                                                      String message) {
        return DocumentEnrichmentJobResponse.builder()
                .documentId(document.getId())
                .documentNodeId(nodeId)
                .documentStatus(document.getStatus())
                .enrichmentStatus(document.getEnrichmentStatus())
                .artifactTypes(artifactTypes)
                .forceRegenerate(forceRegenerate)
                .message(message)
                .queuedAt(LocalDateTime.now())
                .build();
    }

    private DocumentNodeArtifactResponse toResponse(DocumentNodeArtifact artifact) {
        DocumentNode node = artifact.getDocumentNode();
        Document document = artifact.getDocument();
        return DocumentNodeArtifactResponse.builder()
                .id(artifact.getId())
                .documentId(document == null ? null : document.getId())
                .documentNodeId(node == null ? null : node.getId())
                .nodeType(node == null ? null : node.getNodeType())
                .nodeTitle(node == null ? null : node.getTitle())
                .sectionPath(node == null ? null : node.getSectionPath())
                .artifactType(artifact.getArtifactType())
                .status(artifact.getStatus())
                .promptVersion(artifact.getPromptVersion())
                .model(artifact.getModel())
                .sourceHash(artifact.getSourceHash())
                .tokenCount(artifact.getTokenCount())
                .errorMessage(artifact.getErrorMessage())
                .contentJsonb(artifact.getContentJsonb())
                .createdAt(artifact.getCreatedAt())
                .updatedAt(artifact.getUpdatedAt())
                .build();
    }
}
