package com.example.teacherassistantai.integration.ai;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.exception.BackgroundRateLimitedException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DigitalOceanAiRateLimiterTest {

    @Test
    void handle429_pauseAllOn429DoesNotPauseDifferentEnrichmentWorkload() {
        RagProperties properties = new RagProperties();
        properties.getAi().getEnrichment().setPauseAllOn429(true);
        properties.getAi().getEnrichment().setSummaryModel("summary-model");
        properties.getAi().getEnrichment().setReviewQuestionModel("review-model");
        AiModelRoutingService routingService = new AiModelRoutingService(properties);
        RedisTemplate<String, String> redisTemplate = redisTemplate();
        DigitalOceanAiRateLimiter rateLimiter = new DigitalOceanAiRateLimiter(
                redisTemplate,
                properties,
                routingService,
                new DigitalOceanTokenRateLimitTracker(redisTemplate)
        );

        assertThrows(BackgroundRateLimitedException.class, () -> rateLimiter.handle429(
                AiWorkload.ENRICH_SUMMARY,
                AiModelRoutingService.ACCOUNT_ENRICHMENT,
                "summary-model",
                null
        ));

        assertThat(rateLimiter.isPaused(AiWorkload.ENRICH_SUMMARY)).isTrue();
        assertThat(rateLimiter.isPaused(AiWorkload.ENRICH_REVIEW_QUESTION)).isFalse();
    }

    @SuppressWarnings("unchecked")
    private RedisTemplate<String, String> redisTemplate() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        Map<String, String> values = new HashMap<>();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenAnswer(invocation -> values.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            values.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        return redisTemplate;
    }
}
