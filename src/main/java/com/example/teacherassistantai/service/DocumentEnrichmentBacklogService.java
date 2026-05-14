package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DocumentEnrichmentBacklogService {

    private final DocumentRepository documentRepository;
    private final DocumentNodeArtifactRepository artifactRepository;
    private final RagProperties ragProperties;

    public boolean hasSummaryBacklog(Long documentId) {
        if (documentId == null) {
            return false;
        }
        boolean summarising = documentRepository.findById(documentId)
                .map(document -> document.getStatus() == DocumentStatus.SUMMARISING)
                .orElse(false);
        if (summarising) {
            return true;
        }

        List<DocumentNodeArtifactStatus> backlogStatuses = new ArrayList<>(List.of(
                DocumentNodeArtifactStatus.PENDING,
                DocumentNodeArtifactStatus.RUNNING,
                DocumentNodeArtifactStatus.RATE_LIMITED
        ));
        if (ragProperties.getEnrichment().isAutoRetryOnPartialFailed()) {
            backlogStatuses.add(DocumentNodeArtifactStatus.FAILED);
        }
        return artifactRepository.existsByDocumentIdAndArtifactTypeAndStatusIn(
                documentId,
                DocumentNodeArtifactType.SUMMARY,
                backlogStatuses
        );
    }
}
