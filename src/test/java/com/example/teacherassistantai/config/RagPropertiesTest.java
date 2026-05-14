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
            assertThat(properties.getAi().getEnrichment().getSummaryModel()).isEqualTo("openai-gpt-5-mini");
            assertThat(properties.getAi().getEnrichment().getReviewQuestionModel()).isEqualTo("openai-gpt-oss-120b");
            assertThat(properties.getEnrichment().getReviewQuestionComposition().getMaxQuestionsPerChapterInPart()).isEqualTo(5);
            assertThat(properties.getEnrichment().getReviewQuestionComposition().getMaxQuestionsPerChapterInDocument()).isEqualTo(3);
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
                        "application.rag.ai.enrichment.base-url=https://enrichment.test",
                        "application.rag.ai.enrichment.api-key=test-key",
                        "application.rag.ai.enrichment.summary-model=test-summary",
                        "application.rag.ai.enrichment.review-question-model=test-question",
                        "application.rag.enrichment.review-question-composition.max-questions-per-chapter-in-part=7",
                        "application.rag.enrichment.review-question-composition.max-total-questions-in-document=25"
                )
                .run(context -> {
                    RagProperties properties = context.getBean(RagProperties.class);

                    assertThat(properties.getEmbeddingDimensions()).isEqualTo(1024);
                    assertThat(properties.getAi().getBaseUrl()).isEqualTo("https://example.test");
                    assertThat(properties.getAi().getChatModel()).isEqualTo("test-chat");
                    assertThat(properties.getAi().getEmbeddingModel()).isEqualTo("test-embedding");
                    assertThat(properties.getAi().getTimeoutSeconds()).isEqualTo(45);
                    assertThat(properties.getAi().getEnrichment().getBaseUrl()).isEqualTo("https://enrichment.test");
                    assertThat(properties.getAi().getEnrichment().getApiKey()).isEqualTo("test-key");
                    assertThat(properties.getAi().getEnrichment().getSummaryModel()).isEqualTo("test-summary");
                    assertThat(properties.getAi().getEnrichment().getReviewQuestionModel()).isEqualTo("test-question");
                    assertThat(properties.getEnrichment().getReviewQuestionComposition().getMaxQuestionsPerChapterInPart()).isEqualTo(7);
                    assertThat(properties.getEnrichment().getReviewQuestionComposition().getMaxTotalQuestionsInDocument()).isEqualTo(25);
                });
    }

    @Configuration
    @EnableConfigurationProperties(RagProperties.class)
    static class TestConfig {
    }
}
