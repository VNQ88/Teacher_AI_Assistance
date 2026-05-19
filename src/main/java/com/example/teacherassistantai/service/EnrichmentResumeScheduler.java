package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.integration.ai.AiModelRoute;
import com.example.teacherassistantai.integration.ai.AiModelRoutingService;
import com.example.teacherassistantai.integration.ai.AiWorkload;
import com.example.teacherassistantai.integration.ai.DigitalOceanAiRateLimiter;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnrichmentResumeScheduler {

    private final DocumentNodeArtifactRepository artifactRepository;
    private final DocumentEnrichmentService enrichmentService;
    private final DigitalOceanAiRateLimiter rateLimiter;
    private final RagProperties ragProperties;
    private final AiModelRoutingService aiModelRoutingService;

    @Scheduled(fixedDelay = 120_000)
    @Transactional(readOnly = true)
    public void resumeRateLimitedArtifacts() {
        RagProperties.Ai.RateLimit cfg = ragProperties.getAi().getRateLimit();
        if (!cfg.isEnabled()) return;

        AiModelRoute summaryRoute = aiModelRoutingService.route(AiWorkload.ENRICH_SUMMARY);
        AiModelRoute reviewRoute = aiModelRoutingService.route(AiWorkload.ENRICH_REVIEW_QUESTION);
        boolean summaryPaused = rateLimiter.isPaused(summaryRoute.workload(), summaryRoute.accountAlias(), summaryRoute.model());
        boolean reviewPaused = rateLimiter.isPaused(reviewRoute.workload(), reviewRoute.accountAlias(), reviewRoute.model());
        if (summaryPaused && reviewPaused) {
            log.debug("Background resume check: summary and review question are still paused, skipping");
            return;
        }

        List<DocumentNodeArtifact> summaryCandidates = summaryPaused
                ? List.of()
                : artifactRepository.findRateLimitedByArtifactTypeOrderByUpdatedAtAsc(DocumentNodeArtifactType.SUMMARY);
        List<DocumentNodeArtifact> reviewCandidates = reviewPaused
                ? List.of()
                : artifactRepository.findRateLimitedByArtifactTypeOrderByUpdatedAtAsc(DocumentNodeArtifactType.REVIEW_QUESTION_SET);
        if (summaryCandidates.isEmpty() && reviewCandidates.isEmpty()) return;

        List<DocumentNodeArtifact> batch = new ArrayList<>();
        for (DocumentNodeArtifact artifact : summaryCandidates) {
            if (batch.size() >= cfg.getBackgroundResumeBatchSize()) {
                break;
            }
            batch.add(artifact);
        }
        int reviewBatchSize = 0;
        for (DocumentNodeArtifact artifact : reviewCandidates) {
            if (batch.size() >= cfg.getBackgroundResumeBatchSize()) {
                break;
            }
            if (reviewBatchSize >= ragProperties.getEnrichment().getReviewQuestionResumeBatchSize()) {
                break;
            }
            batch.add(artifact);
            reviewBatchSize++;
        }
        if (batch.isEmpty()) return;

        log.info("Background resume: resuming {} RATE_LIMITED artifacts (summaryCandidates={}, reviewCandidates={})",
                batch.size(), summaryCandidates.size(), reviewCandidates.size());

        for (DocumentNodeArtifact artifact : batch) {
            Long nodeId = artifact.getDocumentNode().getId();
            log.info("Background resume: enqueuing nodeId={} type={}", nodeId, artifact.getArtifactType());
            enrichmentService.enqueueNodeEnrichment(nodeId, false, List.of(artifact.getArtifactType()));
        }
    }
}
