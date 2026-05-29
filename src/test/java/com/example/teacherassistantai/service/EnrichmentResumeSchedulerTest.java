package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.integration.ai.AiModelRoute;
import com.example.teacherassistantai.integration.ai.AiModelRoutingService;
import com.example.teacherassistantai.integration.ai.AiWorkload;
import com.example.teacherassistantai.integration.ai.DigitalOceanAiRateLimiter;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnrichmentResumeSchedulerTest {

    private DocumentNodeArtifactRepository artifactRepository;
    private DocumentEnrichmentService enrichmentService;
    private DigitalOceanAiRateLimiter rateLimiter;
    private RagProperties ragProperties;
    private AiModelRoutingService aiModelRoutingService;
    private EnrichmentResumeScheduler scheduler;

    private final AiModelRoute summaryRoute = new AiModelRoute(
            AiWorkload.ENRICH_SUMMARY,
            AiModelRoutingService.ACCOUNT_ENRICHMENT,
            "https://example.test",
            "secret",
            "summary-model",
            true
    );
    private final AiModelRoute reviewRoute = new AiModelRoute(
            AiWorkload.ENRICH_REVIEW_QUESTION,
            AiModelRoutingService.ACCOUNT_ENRICHMENT,
            "https://example.test",
            "secret",
            "review-model",
            true
    );

    @BeforeEach
    void setUp() {
        artifactRepository = mock(DocumentNodeArtifactRepository.class);
        enrichmentService = mock(DocumentEnrichmentService.class);
        rateLimiter = mock(DigitalOceanAiRateLimiter.class);
        ragProperties = new RagProperties();
        aiModelRoutingService = mock(AiModelRoutingService.class);
        scheduler = new EnrichmentResumeScheduler(
                artifactRepository,
                enrichmentService,
                rateLimiter,
                ragProperties,
                aiModelRoutingService
        );

        when(aiModelRoutingService.route(AiWorkload.ENRICH_SUMMARY)).thenReturn(summaryRoute);
        when(aiModelRoutingService.route(AiWorkload.ENRICH_REVIEW_QUESTION)).thenReturn(reviewRoute);
    }

    @Test
    void resumeRateLimitedArtifacts_whenReviewPaused_stillEnqueuesSummary() {
        DocumentNodeArtifact summary = artifact(203L, 3917L, DocumentNodeArtifactType.SUMMARY);
        when(rateLimiter.isPaused(summaryRoute.workload(), summaryRoute.accountAlias(), summaryRoute.model())).thenReturn(false);
        when(rateLimiter.isPaused(reviewRoute.workload(), reviewRoute.accountAlias(), reviewRoute.model())).thenReturn(true);
        when(artifactRepository.findRateLimitedByArtifactTypeOrderByUpdatedAtAsc(DocumentNodeArtifactType.SUMMARY))
                .thenReturn(List.of(summary));

        scheduler.resumeRateLimitedArtifacts();

        verify(enrichmentService).enqueueNodeEnrichment(
                eq(3917L),
                eq(false),
                eq(List.of(DocumentNodeArtifactType.SUMMARY))
        );
        verify(artifactRepository, never())
                .findRateLimitedByArtifactTypeOrderByUpdatedAtAsc(DocumentNodeArtifactType.REVIEW_QUESTION_SET);
    }

    @Test
    void resumeRateLimitedArtifacts_whenSummaryPaused_canStillEnqueueReviewWithoutSummaryBacklog() {
        DocumentNodeArtifact review = artifact(203L, 3923L, DocumentNodeArtifactType.REVIEW_QUESTION_SET);
        when(rateLimiter.isPaused(summaryRoute.workload(), summaryRoute.accountAlias(), summaryRoute.model())).thenReturn(true);
        when(rateLimiter.isPaused(reviewRoute.workload(), reviewRoute.accountAlias(), reviewRoute.model())).thenReturn(false);
        when(artifactRepository.findRateLimitedByArtifactTypeOrderByUpdatedAtAsc(DocumentNodeArtifactType.REVIEW_QUESTION_SET))
                .thenReturn(List.of(review));

        scheduler.resumeRateLimitedArtifacts();

        verify(enrichmentService).enqueueNodeEnrichment(
                eq(3923L),
                eq(false),
                eq(List.of(DocumentNodeArtifactType.REVIEW_QUESTION_SET))
        );
        verify(artifactRepository, never())
                .findRateLimitedByArtifactTypeOrderByUpdatedAtAsc(DocumentNodeArtifactType.SUMMARY);
    }

    private DocumentNodeArtifact artifact(Long documentId, Long nodeId, DocumentNodeArtifactType artifactType) {
        Document document = new Document();
        document.setId(documentId);
        DocumentNode node = new DocumentNode();
        node.setId(nodeId);
        node.setDocument(document);
        DocumentNodeArtifact artifact = new DocumentNodeArtifact();
        artifact.setDocument(document);
        artifact.setDocumentNode(node);
        artifact.setArtifactType(artifactType);
        return artifact;
    }
}
