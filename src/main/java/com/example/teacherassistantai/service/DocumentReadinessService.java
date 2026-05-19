package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentEnrichmentStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DocumentReadinessService {

    public static final int READY_CHAPTER_SUMMARY_PERCENT = 75;
    public static final int READY_CHAPTER_QUESTION_PERCENT = 75;

    private final DocumentRepository documentRepository;
    private final DocumentNodeRepository documentNodeRepository;
    private final DocumentNodeArtifactRepository artifactRepository;
    private final RagProperties ragProperties;
    private final TransactionTemplate transactionTemplate;

    public DocumentReadySnapshot snapshot(Long documentId) {
        ChapterArtifactReadiness summaryReadiness = chapterArtifactReadiness(
                documentId,
                DocumentNodeArtifactType.SUMMARY,
                List.of(DocumentNodeArtifactStatus.COMPLETED),
                READY_CHAPTER_SUMMARY_PERCENT
        );
        ChapterArtifactReadiness questionReadiness = chapterArtifactReadiness(
                documentId,
                DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                List.of(DocumentNodeArtifactStatus.COMPLETED, DocumentNodeArtifactStatus.SKIPPED),
                READY_CHAPTER_QUESTION_PERCENT
        );
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with id: " + documentId));
        return new DocumentReadySnapshot(
                summaryReadiness,
                questionReadiness,
                retryMaxReached(document)
        );
    }

    public Document refreshDocumentReadyStatus(Long documentId) {
        return transactionTemplate.execute(status -> {
            Document document = documentRepository.findById(documentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Document not found with id: " + documentId));
            if (document.getStatus() == DocumentStatus.FAILED) {
                return document;
            }

            ChapterArtifactReadiness summaryReadiness = chapterArtifactReadiness(
                    documentId,
                    DocumentNodeArtifactType.SUMMARY,
                    List.of(DocumentNodeArtifactStatus.COMPLETED),
                    READY_CHAPTER_SUMMARY_PERCENT
            );
            ChapterArtifactReadiness questionReadiness = chapterArtifactReadiness(
                    documentId,
                    DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                    List.of(DocumentNodeArtifactStatus.COMPLETED, DocumentNodeArtifactStatus.SKIPPED),
                    READY_CHAPTER_QUESTION_PERCENT
            );
            boolean retryMaxReached = retryMaxReached(document);

            document.setEnrichmentCompletedAt(LocalDateTime.now());
            if (!summaryReadiness.ready()) {
                document.setStatus(DocumentStatus.SUMMARISING);
                document.setEnrichmentStatus(DocumentEnrichmentStatus.PARTIAL_FAILED);
                document.setEnrichmentError("Chapter summary readiness is %d/%d; document requires at least %d%% completed chapter summaries"
                        .formatted(summaryReadiness.processedChapters(), summaryReadiness.totalChapters(), READY_CHAPTER_SUMMARY_PERCENT));
            } else if (questionReadiness.ready()) {
                document.setStatus(DocumentStatus.READY);
                if (summaryReadiness.allProcessed() && questionReadiness.allProcessed()) {
                    document.setEnrichmentStatus(DocumentEnrichmentStatus.ENRICHED);
                    document.setEnrichmentError(null);
                } else {
                    document.setEnrichmentStatus(DocumentEnrichmentStatus.PARTIAL_FAILED);
                    document.setEnrichmentError("Chapter question readiness reached %d/%d; remaining artifacts will continue in background"
                            .formatted(questionReadiness.processedChapters(), questionReadiness.totalChapters()));
                }
            } else if (retryMaxReached) {
                document.setStatus(DocumentStatus.READY);
                document.setEnrichmentStatus(DocumentEnrichmentStatus.PARTIAL_FAILED);
                document.setEnrichmentError("Chapter question readiness reached %d/%d after max retries"
                        .formatted(questionReadiness.processedChapters(), questionReadiness.totalChapters()));
            } else {
                document.setStatus(DocumentStatus.SUMMARISING);
                document.setEnrichmentStatus(DocumentEnrichmentStatus.PARTIAL_FAILED);
                document.setEnrichmentError("Chapter question readiness is %d/%d; document requires at least %d%% completed or skipped chapter question sets"
                        .formatted(questionReadiness.processedChapters(), questionReadiness.totalChapters(), READY_CHAPTER_QUESTION_PERCENT));
            }
            return documentRepository.save(document);
        });
    }

    private ChapterArtifactReadiness chapterArtifactReadiness(Long documentId,
                                                              DocumentNodeArtifactType artifactType,
                                                              List<DocumentNodeArtifactStatus> processedStatuses,
                                                              int readyPercent) {
        List<DocumentNode> chapters = documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(
                documentId,
                "chapter"
        );
        if (chapters == null || chapters.isEmpty()) {
            return new ChapterArtifactReadiness(0, 0, true);
        }

        List<Long> chapterIds = chapters.stream()
                .map(DocumentNode::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (chapterIds.isEmpty()) {
            return new ChapterArtifactReadiness(0, chapters.size(), false);
        }

        Set<Long> processedChapterIds = processedStatuses.stream()
                .flatMap(processedStatus -> {
                    List<DocumentNodeArtifact> artifacts = artifactRepository
                            .findByNodeIdsAndArtifactTypeAndStatusOrderByNodeOrderAndUpdatedAt(
                                    chapterIds,
                                    artifactType,
                                    processedStatus
                            );
                    return (artifacts == null ? List.<DocumentNodeArtifact>of() : artifacts).stream();
                })
                .map(DocumentNodeArtifact::getDocumentNode)
                .filter(java.util.Objects::nonNull)
                .map(DocumentNode::getId)
                .filter(chapterIds::contains)
                .collect(Collectors.toSet());

        int processed = processedChapterIds.size();
        int total = chapterIds.size();
        boolean ready = processed * 100 >= total * readyPercent;
        return new ChapterArtifactReadiness(processed, total, ready);
    }

    private boolean retryMaxReached(Document document) {
        int retryCount = document.getEnrichmentRetryCount() == null ? 0 : document.getEnrichmentRetryCount();
        return retryCount >= ragProperties.getEnrichment().getAutoRetryMaxAttempts();
    }

    public record DocumentReadySnapshot(
            ChapterArtifactReadiness summaryReadiness,
            ChapterArtifactReadiness questionReadiness,
            boolean retryMaxReached
    ) {
    }

    public record ChapterArtifactReadiness(
            int processedChapters,
            int totalChapters,
            boolean ready
    ) {
        boolean allProcessed() {
            return totalChapters == 0 || processedChapters >= totalChapters;
        }
    }
}
