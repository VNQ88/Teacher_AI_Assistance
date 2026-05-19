package com.example.teacherassistantai.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;

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
    private int embeddingBatchSize = 64;

    @Min(1)
    private int embeddingConcurrency = 3;

    @Min(1)
    private int maxHistoryMessages = 5;

    private Ai ai = new Ai();

    private Enrichment enrichment = new Enrichment();

    @Data
    public static class Ai {
        @NotBlank
        private String baseUrl = "https://inference.do-ai.run";

        private String apiKey = "";

        @NotBlank
        private String chatModel = "openai-gpt-oss-120b";

        @NotBlank
        private String embeddingModel = "qwen3-embedding-0.6b";

        @Min(1)
        private int timeoutSeconds = 60;

        private EnrichmentAi enrichment = new EnrichmentAi();

        private RateLimit rateLimit = new RateLimit();

        @Data
        public static class RateLimit {
            private boolean enabled = true;

            @Min(1)
            private int totalRequestsPerMinute = 180;

            @Min(1)
            private int totalRequestsPerHour = 4500;

            @Min(1)
            private int backgroundRequestsPerMinute = 65;

            @Min(1)
            private int backgroundRequestsPerHour = 4000;

            @Min(1)
            private int backgroundPauseRemainingThreshold = 500;

            @Min(1)
            private int backgroundResumeRemainingThreshold = 1000;

            @Min(1)
            private int backgroundHourlyPauseMinutes = 10;

            @Min(1)
            private int backgroundResumeBatchSize = 30;

            @Min(1)
            private int enrichmentPauseMinutesOn429 = 10;

            @Min(0)
            private long enrichmentMinRemainingTokensPerMinute = 1_000;

            @Min(0)
            private long enrichmentMinRemainingTokensPerDay = 10_000;
        }

        @Data
        public static class EnrichmentAi {
            @NotBlank
            private String baseUrl = "https://inference.do-ai.run";

            private String apiKey = "";

            @NotBlank
            private String summaryModel = "openai-gpt-5-mini";

            @NotBlank
            private String reviewQuestionModel = "openai-gpt-oss-120b";

            @NotBlank
            private String onDemandReviewQuestionModel = "gemma-4-31B-it";

            @Min(1)
            private int timeoutSeconds = 120;

            @Min(1)
            private int onDemandTimeoutSeconds = 120;

            private boolean pauseAllOn429 = false;
        }
    }

    @Data
    public static class Enrichment {
        private boolean enabled = true;

        private boolean autoRunAfterReady = true;

        @NotBlank
        private String promptVersion = "enrichment-v1";

        private boolean summaryEnabled = true;

        private boolean reviewQuestionsEnabled = true;

        @Min(1)
        private int defaultReviewQuestionMinCount = 15;

        @Min(1)
        private int defaultReviewQuestionMaxCount = 20;

        private Map<String, ReviewQuestionCountRange> reviewQuestionCounts = defaultReviewQuestionCounts();

        @Min(1)
        private int reviewQuestionResumeBatchSize = 10;

        private ReviewQuestionComposition reviewQuestionComposition = new ReviewQuestionComposition();

        private ReviewQuestionMixedInput reviewQuestionMixedInput = new ReviewQuestionMixedInput();

        private Repair repair = new Repair();

        @Min(1)
        private int maxNodeChunks = 120;

        @Min(1)
        private int maxNodeContextChars = 60_000;

        @Min(1)
        private int maxConcurrency = 6;

        @Min(1)
        private int intraDocumentConcurrency = 6;

        private boolean autoRetryOnPartialFailed = true;

        @Min(1)
        private int autoRetryDelayMinutes = 35;

        @Min(1)
        private int autoRetryMaxAttempts = 3;

        @Min(1)
        private int representativeSectionChunks = 3;

        @Min(1)
        private int maxDirectOriginalSummaryChars = 2_000;

        @Min(1)
        private int parentSummaryMaxChildChars = 3_000;

        @Min(1)
        private int subsectionSummaryMaxChars = 1_200;

        @Min(1)
        private int sectionSummaryMaxKeyPoints = 4;

        @Min(1)
        private int chapterSummaryMaxKeyPoints = 8;

        private static Map<String, ReviewQuestionCountRange> defaultReviewQuestionCounts() {
            Map<String, ReviewQuestionCountRange> counts = new LinkedHashMap<>();
            counts.put("subsection_level2", range(5, 10));
            counts.put("subsection", range(10, 15));
            counts.put("section", range(15, 20));
            counts.put("chapter", range(20, 25));
            return counts;
        }

        private static ReviewQuestionCountRange range(int min, int max) {
            ReviewQuestionCountRange range = new ReviewQuestionCountRange();
            range.setMin(min);
            range.setMax(max);
            return range;
        }

        @Data
        public static class ReviewQuestionCountRange {
            @Min(1)
            private int min;

            @Min(1)
            private int max;
        }

        @Data
        public static class ReviewQuestionComposition {
            @Min(1)
            private int maxQuestionsPerChapterInPart = 5;

            @Min(1)
            private int maxQuestionsPerChapterInDocument = 3;

            @Min(1)
            private int maxTotalQuestionsInPart = 30;

            @Min(1)
            private int maxTotalQuestionsInDocument = 50;

            @Min(1)
            private int maxMissingQueuePerRequest = 10;
        }

        @Data
        public static class ReviewQuestionMixedInput {
            private boolean enabled = true;

            private double summaryTargetRatio = 0.5;

            @Min(1)
            private int maxChildSummaryChars = 2_000;

            @Min(1)
            private int maxFallbackChunksPerChild = 3;

            @Min(1)
            private int maxRepresentativeChunksPerChild = 3;

            @Min(1)
            private int maxTotalContextChars = 48_000;

            private boolean requireSourceMode = true;
        }

        @Data
        public static class Repair {
            @Min(1)
            private int maxRawResponseChars = 12_000;

            @Min(1)
            private int maxAttempts = 2;
        }
    }
}
