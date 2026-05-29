package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentEnrichmentStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.integration.ai.RateLimitTracker;
import com.example.teacherassistantai.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnrichmentAutoRetryService {

    private final DocumentRepository documentRepository;
    private final DocumentEnrichmentService enrichmentService;
    private final RateLimitTracker rateLimitTracker;
    private final RagProperties ragProperties;

    @Scheduled(fixedDelay = 600_000)
    @Transactional
    public void retryPartialFailedDocuments() {
        if (!ragProperties.getEnrichment().isAutoRetryOnPartialFailed()) return;

        if (rateLimitTracker.getRemaining() < 100
                && rateLimitTracker.getResetAt().isAfter(Instant.now())) {
            log.info("Auto-retry skipped: quota not reset yet (remaining={}, resetAt={})",
                    rateLimitTracker.getRemaining(), rateLimitTracker.getResetAt());
            return;
        }

        int delayMinutes = ragProperties.getEnrichment().getAutoRetryDelayMinutes();
        int maxRetries = ragProperties.getEnrichment().getAutoRetryMaxAttempts();
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(delayMinutes);

        List<Document> candidates = documentRepository.findRetryableDocuments(
                DocumentEnrichmentStatus.PARTIAL_FAILED, cutoff, maxRetries);

        if (candidates.isEmpty()) return;

        log.info("Auto-retry: {} PARTIAL_FAILED documents eligible", candidates.size());
        for (Document doc : candidates) {
            doc.setEnrichmentRetryCount(doc.getEnrichmentRetryCount() + 1);
            documentRepository.save(doc);
            log.info("Auto-retry: enqueuing documentId={} attempt={}/{}",
                    doc.getId(), doc.getEnrichmentRetryCount(), maxRetries);
            enrichmentService.enqueueDocumentEnrichment(
                    doc.getId(), false,
                    List.of(DocumentNodeArtifactType.SUMMARY, DocumentNodeArtifactType.REVIEW_QUESTION_SET));
        }
    }
}
