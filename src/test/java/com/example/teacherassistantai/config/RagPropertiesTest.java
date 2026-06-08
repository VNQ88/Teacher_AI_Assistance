package com.example.teacherassistantai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class RagPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void defaults_shouldUseDigitalOceanQwen3Configuration() {
        contextRunner.run(context -> {
            RagProperties properties = context.getBean(RagProperties.class);

            assertThat(properties.getEmbeddingDimensions()).isEqualTo(1024);
            assertThat(properties.getAi().getBaseUrl()).isEqualTo("https://inference.do-ai.run");
            assertThat(properties.getAi().getChatModel()).isEqualTo("openai-gpt-oss-120b");
            assertThat(properties.getAi().getEmbeddingModel()).isEqualTo("qwen3-embedding-0.6b");
            assertThat(properties.getAi().getQueryInstructionPrefix())
                    .isEqualTo("Instruct: Given an educational question in Vietnamese, retrieve relevant textbook passages that answer it.\nQuery: ");
            assertThat(properties.getAi().getEnrichment().getSummaryModel()).isEqualTo("openai-gpt-5-mini");
            assertThat(properties.getAi().getEnrichment().getReviewQuestionModel()).isEqualTo("openai-gpt-oss-120b");
            assertThat(properties.getAi().getEnrichment().getOnDemandReviewQuestionModel()).isEqualTo("gemma-4-31B-it");
            assertThat(properties.getAi().getEnrichment().getOnDemandTimeoutSeconds()).isEqualTo(120);
            assertThat(properties.getEnrichment().getMaxDirectOriginalSummaryChars()).isEqualTo(2_000);
            assertThat(properties.getEnrichment().getMaxConcurrency()).isEqualTo(6);
            assertThat(properties.getEnrichment().getIntraDocumentConcurrency()).isEqualTo(6);
            assertThat(properties.getEnrichment().getReviewQuestionMixedInput().isEnabled()).isTrue();
            assertThat(properties.getEnrichment().getReviewQuestionMixedInput().getSummaryTargetRatio()).isEqualTo(0.5);
            assertThat(properties.getEnrichment().getReviewQuestionMixedInput().getMaxChildSummaryChars()).isEqualTo(2_000);
            assertThat(properties.getEnrichment().getRepair().getMaxRawResponseChars()).isEqualTo(12_000);
            assertThat(properties.getEnrichment().getRepair().getMaxAttempts()).isEqualTo(2);
            assertThat(properties.getEnrichment().getReviewQuestionComposition().getMaxQuestionsPerChapterInPart()).isEqualTo(5);
            assertThat(properties.getEnrichment().getReviewQuestionComposition().getMaxQuestionsPerChapterInDocument()).isEqualTo(3);
            assertThat(properties.getRetrieval().getScopedVector().isEnabled()).isTrue();
            assertThat(properties.getRetrieval().getScopedVector().getMinConfidence()).isEqualTo(0.85);
            assertThat(properties.getRetrieval().getCoarseToFine().isEnabled()).isTrue();
            assertThat(properties.getRetrieval().getCoarseToFine().getCoarseTopK()).isEqualTo(4);
            assertThat(properties.getRetrieval().getCoarseToFine().getFineCandidateTopKPerNode()).isEqualTo(8);
            assertThat(properties.getRetrieval().getCoarseToFine().getFlatGuardrailCandidateTopK()).isEqualTo(8);
            assertThat(properties.getRetrieval().getCoarseToFine().getMaxCoarseDistance()).isEqualTo(0.0);
            assertThat(properties.getRetrieval().getCoarseToFine().getMaxFineCandidates()).isEqualTo(32);
            assertThat(properties.getRetrieval().getCoarseToFine().isIncludeDocumentRoot()).isFalse();
        });
    }

    @Test
    void binding_shouldOverrideAiProperties() {
        contextRunner
                .withPropertyValues(
                        "application.rag.embedding-dimensions=1024",
                        "application.rag.ai.base-url=https://example.test",
                        "application.rag.ai.chat-model=test-chat",
                        "application.rag.ai.embedding-model=test-embedding",
                        "application.rag.ai.timeout-seconds=45",
                        "application.rag.ai.query-instruction-prefix=custom query prefix:",
                        "application.rag.ai.enrichment.base-url=https://enrichment.test",
                        "application.rag.ai.enrichment.api-key=test-key",
                        "application.rag.ai.enrichment.summary-model=test-summary",
                        "application.rag.ai.enrichment.review-question-model=test-question",
                        "application.rag.ai.api-key=rag-key",
                        "application.rag.ai.enrichment.ondemand-review-question-model=ondemand-test",
                        "application.rag.ai.enrichment.ondemand-timeout-seconds=90",
                        "application.rag.enrichment.max-direct-original-summary-chars=1500",
                        "application.rag.enrichment.review-question-mixed-input.enabled=false",
                        "application.rag.enrichment.review-question-mixed-input.summary-target-ratio=0.4",
                        "application.rag.enrichment.review-question-mixed-input.max-child-summary-chars=1800",
                        "application.rag.enrichment.repair.max-raw-response-chars=8000",
                        "application.rag.enrichment.repair.max-attempts=3",
                        "application.rag.enrichment.review-question-composition.max-questions-per-chapter-in-part=7",
                        "application.rag.enrichment.review-question-composition.max-total-questions-in-document=25",
                        "application.rag.retrieval.scoped-vector.enabled=false",
                        "application.rag.retrieval.scoped-vector.min-confidence=0.75",
                        "application.rag.retrieval.coarse-to-fine.enabled=false",
                        "application.rag.retrieval.coarse-to-fine.coarse-top-k=5",
                        "application.rag.retrieval.coarse-to-fine.fine-candidate-top-k-per-node=9",
                        "application.rag.retrieval.coarse-to-fine.flat-guardrail-candidate-top-k=10",
                        "application.rag.retrieval.coarse-to-fine.max-coarse-distance=0.42",
                        "application.rag.retrieval.coarse-to-fine.max-fine-candidates=40",
                        "application.rag.retrieval.coarse-to-fine.include-document-root=true"
                )
                .run(context -> {
                    RagProperties properties = context.getBean(RagProperties.class);

                    assertThat(properties.getEmbeddingDimensions()).isEqualTo(1024);
                    assertThat(properties.getAi().getBaseUrl()).isEqualTo("https://example.test");
                    assertThat(properties.getAi().getChatModel()).isEqualTo("test-chat");
                    assertThat(properties.getAi().getEmbeddingModel()).isEqualTo("test-embedding");
                    assertThat(properties.getAi().getTimeoutSeconds()).isEqualTo(45);
                    assertThat(properties.getAi().getQueryInstructionPrefix()).isEqualTo("custom query prefix:");
                    assertThat(properties.getAi().getEnrichment().getBaseUrl()).isEqualTo("https://enrichment.test");
                    assertThat(properties.getAi().getEnrichment().getApiKey()).isEqualTo("test-key");
                    assertThat(properties.getAi().getEnrichment().getSummaryModel()).isEqualTo("test-summary");
                    assertThat(properties.getAi().getEnrichment().getReviewQuestionModel()).isEqualTo("test-question");
                    assertThat(properties.getAi().getApiKey()).isEqualTo("rag-key");
                    assertThat(properties.getAi().getEnrichment().getOnDemandReviewQuestionModel()).isEqualTo("ondemand-test");
                    assertThat(properties.getAi().getEnrichment().getOnDemandTimeoutSeconds()).isEqualTo(90);
                    assertThat(properties.getEnrichment().getMaxDirectOriginalSummaryChars()).isEqualTo(1500);
                    assertThat(properties.getEnrichment().getReviewQuestionMixedInput().isEnabled()).isFalse();
                    assertThat(properties.getEnrichment().getReviewQuestionMixedInput().getSummaryTargetRatio()).isEqualTo(0.4);
                    assertThat(properties.getEnrichment().getReviewQuestionMixedInput().getMaxChildSummaryChars()).isEqualTo(1800);
                    assertThat(properties.getEnrichment().getRepair().getMaxRawResponseChars()).isEqualTo(8000);
                    assertThat(properties.getEnrichment().getRepair().getMaxAttempts()).isEqualTo(3);
                    assertThat(properties.getEnrichment().getReviewQuestionComposition().getMaxQuestionsPerChapterInPart()).isEqualTo(7);
                    assertThat(properties.getEnrichment().getReviewQuestionComposition().getMaxTotalQuestionsInDocument()).isEqualTo(25);
                    assertThat(properties.getRetrieval().getScopedVector().isEnabled()).isFalse();
                    assertThat(properties.getRetrieval().getScopedVector().getMinConfidence()).isEqualTo(0.75);
                    assertThat(properties.getRetrieval().getCoarseToFine().isEnabled()).isFalse();
                    assertThat(properties.getRetrieval().getCoarseToFine().getCoarseTopK()).isEqualTo(5);
                    assertThat(properties.getRetrieval().getCoarseToFine().getFineCandidateTopKPerNode()).isEqualTo(9);
                    assertThat(properties.getRetrieval().getCoarseToFine().getFlatGuardrailCandidateTopK()).isEqualTo(10);
                    assertThat(properties.getRetrieval().getCoarseToFine().getMaxCoarseDistance()).isEqualTo(0.42);
                    assertThat(properties.getRetrieval().getCoarseToFine().getMaxFineCandidates()).isEqualTo(40);
                    assertThat(properties.getRetrieval().getCoarseToFine().isIncludeDocumentRoot()).isTrue();
                });
    }

    @Configuration
    @EnableConfigurationProperties(RagProperties.class)
    static class TestConfig {
    }
}
