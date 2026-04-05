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
    private int embeddingDimensions = 3072;

    @Min(1)
    private int maxHistoryMessages = 8;

    private Gemini gemini = new Gemini();

    @Data
    public static class Gemini {
        @NotBlank
        private String apiKey = "";

        @NotBlank
        private String baseUrl = "https://generativelanguage.googleapis.com";

        @NotBlank
        private String llmModel = "gemini-3.1-flash-lite";

        @NotBlank
        private String embeddingModel = "gemini-embedding-2";

        @Min(1)
        private int timeoutSeconds = 60;
    }
}
