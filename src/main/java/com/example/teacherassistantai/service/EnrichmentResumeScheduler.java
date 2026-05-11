package com.example.teacherassistantai.service;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.integration.ai.DigitalOceanAiRateLimiter;
import com.example.teacherassistantai.integration.ai.RateLimitTracker;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnrichmentResumeScheduler {

    private final DocumentNodeArtifactRepository artifactRepository;
    private final DocumentEnrichmentService enrichmentService;
    private final DigitalOceanAiRateLimiter rateLimiter;
    private final RateLimitTracker rateLimitTracker;
    private final RagProperties ragProperties;

    @Scheduled(fixedDelay = 120_000)
    public void resumeRateLimitedArtifacts() {
        RagProperties.Ai.RateLimit cfg = ragProperties.getAi().getRateLimit();
        if (!cfg.isEnabled()) return;

        if (rateLimiter.isBackgroundPaused()) {
            log.debug("Background resume check: still paused, skipping");
            return;
        }

        int remaining = rateLimitTracker.getRemaining();
        log.debug("Background resume check remaining={}", remaining);
        if (remaining < cfg.getBackgroundResumeRemainingThreshold()) {
            log.info("Background resume check remaining={} threshold={}: quota insufficient, skipping",
                    remaining, cfg.getBackgroundResumeRemainingThreshold());
            return;
        }

        List<DocumentNodeArtifact> candidates = artifactRepository.findRateLimitedOrderByUpdatedAtAsc();
        if (candidates.isEmpty()) return;

        List<DocumentNodeArtifact> batch = candidates.stream()
                .limit(cfg.getBackgroundResumeBatchSize())
                .toList();

        log.info("Background resume: resuming {} RATE_LIMITED artifacts (total={})",
                batch.size(), candidates.size());

        for (DocumentNodeArtifact artifact : batch) {
            Long nodeId = artifact.getDocumentNode().getId();
            log.info("Background resume: enqueuing nodeId={} type={}", nodeId, artifact.getArtifactType());
            enrichmentService.enqueueNodeEnrichment(nodeId, false, List.of(artifact.getArtifactType()));
        }
    }
}
