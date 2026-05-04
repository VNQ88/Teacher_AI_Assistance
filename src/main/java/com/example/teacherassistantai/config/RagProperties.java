package com.example.teacherassistantai.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "application.rag")
public class RagProperties {

    @Min(1)
    private int topK = 6;

    @Min(1)
    private int maxTopK = 8;

    @Min(1)
    private int candidateTopK = 24;

    @Min(1)
    private int minChunkChars = 40;

    @Min(1)
    private int embeddingDimensions = 1024;

    @Min(1)
    private int maxHistoryMessages = 5;

    private Ai ai = new Ai();

    @Data
    public static class Ai {
        @NotBlank
        private String baseUrl = "https://inference.do-ai.run";

        @NotBlank
        private String chatModel = "openai-gpt-oss-120b";

        @NotBlank
        private String embeddingModel = "qwen3-embedding-0.6b";

        @Min(1)
        private int timeoutSeconds = 60;
    }
}
