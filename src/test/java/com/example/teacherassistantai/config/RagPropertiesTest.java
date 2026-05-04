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
                        "application.rag.ai.timeout-seconds=45"
                )
                .run(context -> {
                    RagProperties properties = context.getBean(RagProperties.class);

                    assertThat(properties.getEmbeddingDimensions()).isEqualTo(1024);
                    assertThat(properties.getAi().getBaseUrl()).isEqualTo("https://example.test");
                    assertThat(properties.getAi().getChatModel()).isEqualTo("test-chat");
                    assertThat(properties.getAi().getEmbeddingModel()).isEqualTo("test-embedding");
                    assertThat(properties.getAi().getTimeoutSeconds()).isEqualTo(45);
                });
    }

    @Configuration
    @EnableConfigurationProperties(RagProperties.class)
    static class TestConfig {
    }
}
