package com.example.teacherassistantai.integration.ai;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiModelRoutingService {

    public static final String ACCOUNT_RAG = "RAG";
    public static final String ACCOUNT_ENRICHMENT = "ENRICHMENT";

    private final RagProperties ragProperties;

    public AiModelRoute route(AiWorkload workload) {
        if (workload == AiWorkload.ENRICH_REVIEW_QUESTION_ONDEMAND) {
            // On-demand: RAG account/key, dedicated on-demand model, RAG_CHAT bucket (180/min), raw HTTP client for explicit model + timeout.
            RagProperties.Ai ai = ragProperties.getAi();
            return new AiModelRoute(
                    AiWorkload.RAG_CHAT,
                    ACCOUNT_RAG,
                    ai.getBaseUrl(),
                    ai.getApiKey(),
                    ai.getEnrichment().getOnDemandReviewQuestionModel(),
                    true
            );
        }
        AiWorkload normalized = workload == null ? AiWorkload.RAG_CHAT : workload.normalized();
        RagProperties.Ai ai = ragProperties.getAi();
        return switch (normalized) {
            case RAG_CHAT -> new AiModelRoute(
                    normalized,
                    ACCOUNT_RAG,
                    ai.getBaseUrl(),
                    null,
                    ai.getChatModel(),
                    false
            );
            case EMBEDDING -> new AiModelRoute(
                    normalized,
                    ACCOUNT_RAG,
                    ai.getBaseUrl(),
                    null,
                    ai.getEmbeddingModel(),
                    false
            );
            case ENRICH_SUMMARY -> enrichmentRoute(normalized, ai.getEnrichment().getSummaryModel());
            case ENRICH_REVIEW_QUESTION -> enrichmentRoute(normalized, ai.getEnrichment().getReviewQuestionModel());
            case ENRICH_REVIEW_QUESTION_ONDEMAND -> throw new IllegalStateException("ENRICH_REVIEW_QUESTION_ONDEMAND should have been handled before normalization");
            case INTERACTIVE, BACKGROUND -> throw new IllegalStateException("Workload should have been normalized: " + workload);
        };
    }

    public String enrichmentModelFor(DocumentNodeArtifactType artifactType) {
        if (artifactType == DocumentNodeArtifactType.SUMMARY) {
            return ragProperties.getAi().getEnrichment().getSummaryModel();
        }
        if (artifactType == DocumentNodeArtifactType.REVIEW_QUESTION_SET) {
            return ragProperties.getAi().getEnrichment().getReviewQuestionModel();
        }
        return ragProperties.getAi().getChatModel();
    }

    public String reviewQuestionModelFor(boolean isOnDemand) {
        RagProperties.Ai.EnrichmentAi enrichment = ragProperties.getAi().getEnrichment();
        return isOnDemand ? enrichment.getOnDemandReviewQuestionModel() : enrichment.getReviewQuestionModel();
    }

    private AiModelRoute enrichmentRoute(AiWorkload workload, String model) {
        RagProperties.Ai.EnrichmentAi enrichment = ragProperties.getAi().getEnrichment();
        return new AiModelRoute(
                workload,
                ACCOUNT_ENRICHMENT,
                enrichment.getBaseUrl(),
                enrichment.getApiKey(),
                model,
                true
        );
    }
}
