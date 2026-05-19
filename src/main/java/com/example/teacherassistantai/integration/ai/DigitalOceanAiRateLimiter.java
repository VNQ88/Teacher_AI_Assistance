package com.example.teacherassistantai.integration.ai;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.exception.AiRateLimitedException;
import com.example.teacherassistantai.exception.BackgroundRateLimitedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
public class DigitalOceanAiRateLimiter {

    private static final String ENRICHMENT_ALL_MODELS = "__ALL__";
    private static final String PREFIX_TOTAL_MIN = "ai:rl:total:min:";
    private static final String PREFIX_TOTAL_HOUR = "ai:rl:total:hour:";

    private final RedisTemplate<String, String> redisTemplate;
    private final RagProperties ragProperties;
    private final AiModelRoutingService routingService;
    private final DigitalOceanTokenRateLimitTracker tokenRateLimitTracker;

    public DigitalOceanAiRateLimiter(@Qualifier("aiRateLimitRedisTemplate") RedisTemplate<String, String> redisTemplate,
                                     RagProperties ragProperties,
                                     AiModelRoutingService routingService,
                                     DigitalOceanTokenRateLimitTracker tokenRateLimitTracker) {
        this.redisTemplate = redisTemplate;
        this.ragProperties = ragProperties;
        this.routingService = routingService;
        this.tokenRateLimitTracker = tokenRateLimitTracker;
    }

    public void acquire(AiWorkload workload) {
        AiModelRoute route = routingService.route(workload == null ? AiWorkload.RAG_CHAT : workload);
        acquire(route.workload(), route.accountAlias(), route.model(), null);
    }

    public void acquire(AiWorkload workload, String accountAlias, String model, Integer estimatedPromptTokens) {
        RagProperties.Ai.RateLimit cfg = ragProperties.getAi().getRateLimit();
        if (!cfg.isEnabled()) return;

        AiWorkload normalized = workload == null ? AiWorkload.RAG_CHAT : workload.normalized();
        if (normalized.enrichment()) {
            checkEnrichmentNotPaused(normalized, accountAlias, model);
            checkTokenSnapshotThreshold(normalized, accountAlias, model, cfg);
            log.debug("AI limiter acquire workload={} accountAlias={} model={}", normalized, accountAlias, model);
            return;
        }

        waitForMinuteBudget(PREFIX_TOTAL_MIN, cfg.getTotalRequestsPerMinute(), normalized);
        checkHourBudget(PREFIX_TOTAL_HOUR, cfg.getTotalRequestsPerHour(), normalized);
        incr(PREFIX_TOTAL_MIN + epochMinute(), 90);
        incr(PREFIX_TOTAL_HOUR + epochHour(), 3700);
        log.debug("AI limiter acquire workload={} accountAlias={} model={}", normalized, accountAlias, model);
    }

    public boolean isPaused(AiWorkload workload, String accountAlias, String model) {
        AiWorkload normalized = workload == null ? AiWorkload.RAG_CHAT : workload.normalized();
        if (!normalized.enrichment()) {
            return false;
        }
        return pausedUntil(normalized, accountAlias, model).isAfter(Instant.now());
    }

    public boolean isPaused(AiWorkload workload) {
        AiModelRoute route = routingService.route(workload == null ? AiWorkload.RAG_CHAT : workload);
        return isPaused(route.workload(), route.accountAlias(), route.model());
    }

    public boolean isBackgroundPaused() {
        AiModelRoute summaryRoute = routingService.route(AiWorkload.ENRICH_SUMMARY);
        AiModelRoute reviewRoute = routingService.route(AiWorkload.ENRICH_REVIEW_QUESTION);
        return isPaused(summaryRoute.workload(), summaryRoute.accountAlias(), summaryRoute.model())
                || isPaused(reviewRoute.workload(), reviewRoute.accountAlias(), reviewRoute.model());
    }

    public void handle429(AiWorkload workload,
                          String accountAlias,
                          String model,
                          AiRateLimitSnapshot snapshot) {
        AiWorkload normalized = workload == null ? AiWorkload.RAG_CHAT : workload.normalized();
        if (snapshot != null) {
            tokenRateLimitTracker.update(accountAlias, workloadModelKey(normalized, model), snapshot);
        }
        if (!normalized.enrichment()) {
            throw new AiRateLimitedException("AI provider rate limit exceeded. Please retry later.");
        }

        Instant until = Instant.now().plusSeconds((long) ragProperties.getAi().getRateLimit().getEnrichmentPauseMinutesOn429() * 60);
        pause(normalized, accountAlias, model, until);
        log.info("Enrichment paused until={} reason=429_received workload={} accountAlias={} model={}",
                until, normalized, accountAlias, model);
        throw new BackgroundRateLimitedException(until);
    }

    public void recordSuccess(AiWorkload workload,
                              String accountAlias,
                              String model,
                              AiRateLimitSnapshot snapshot,
                              AiUsage usage) {
        if (snapshot != null) {
            AiWorkload normalized = workload == null ? AiWorkload.RAG_CHAT : workload.normalized();
            tokenRateLimitTracker.update(accountAlias, workloadModelKey(normalized, model), snapshot);
        }
        if (snapshot != null || usage != null) {
            log.info("AI response workload={} accountAlias={} model={} promptTokens={} completionTokens={} totalTokens={} remainingTokensPerMinute={} remainingTokensPerDay={} limitTokensPerMinute={} limitTokensPerDay={}",
                    workload == null ? null : workload.normalized(),
                    accountAlias,
                    model,
                    usage == null ? null : usage.promptTokens(),
                    usage == null ? null : usage.completionTokens(),
                    usage == null ? null : usage.totalTokens(),
                    snapshot == null ? null : snapshot.remainingTokensPerMinute(),
                    snapshot == null ? null : snapshot.remainingTokensPerDay(),
                    snapshot == null ? null : snapshot.limitTokensPerMinute(),
                    snapshot == null ? null : snapshot.limitTokensPerDay());
        }
    }

    public void handleBackground429() {
        AiModelRoute route = routingService.route(AiWorkload.ENRICH_REVIEW_QUESTION);
        handle429(route.workload(), route.accountAlias(), route.model(), null);
    }

    private void checkEnrichmentNotPaused(AiWorkload workload, String accountAlias, String model) {
        Instant pausedUntil = pausedUntil(workload, accountAlias, model);
        if (pausedUntil.isAfter(Instant.now())) {
            log.info("Enrichment request blocked by pause workload={} accountAlias={} model={} pausedUntil={}",
                    workload, accountAlias, model, pausedUntil);
            throw new BackgroundRateLimitedException(pausedUntil);
        }
    }

    private void checkTokenSnapshotThreshold(AiWorkload workload,
                                             String accountAlias,
                                             String model,
                                             RagProperties.Ai.RateLimit cfg) {
        tokenRateLimitTracker.getSnapshot(accountAlias, workloadModelKey(workload, model)).ifPresent(snapshot -> {
            boolean minuteLow = snapshot.remainingTokensPerMinute() != null
                    && snapshot.remainingTokensPerMinute() < cfg.getEnrichmentMinRemainingTokensPerMinute();
            boolean dayLow = snapshot.remainingTokensPerDay() != null
                    && snapshot.remainingTokensPerDay() < cfg.getEnrichmentMinRemainingTokensPerDay();
            if (!minuteLow && !dayLow) {
                return;
            }
            Instant until = Instant.now().plusSeconds((long) cfg.getEnrichmentPauseMinutesOn429() * 60);
            pause(workload, accountAlias, model, until);
            log.info("Enrichment paused until={} reason=token_threshold workload={} accountAlias={} model={} remainingTokensPerMinute={} remainingTokensPerDay={}",
                    until, workload, accountAlias, model,
                    snapshot.remainingTokensPerMinute(), snapshot.remainingTokensPerDay());
            throw new BackgroundRateLimitedException(until);
        });
    }

    private void pause(AiWorkload workload, String accountAlias, String model, Instant until) {
        AiWorkload normalized = workload == null ? AiWorkload.RAG_CHAT : workload.normalized();
        tokenRateLimitTracker.pause(accountAlias, workloadModelKey(normalized, model), until);
        if (ragProperties.getAi().getEnrichment().isPauseAllOn429()) {
            tokenRateLimitTracker.pause(accountAlias, workloadModelKey(normalized, ENRICHMENT_ALL_MODELS), until);
        }
    }

    private Instant pausedUntil(AiWorkload workload, String accountAlias, String model) {
        AiWorkload normalized = workload == null ? AiWorkload.RAG_CHAT : workload.normalized();
        Instant allModels = tokenRateLimitTracker.pausedUntil(accountAlias, workloadModelKey(normalized, ENRICHMENT_ALL_MODELS)).orElse(Instant.EPOCH);
        Instant specificModel = tokenRateLimitTracker.pausedUntil(accountAlias, workloadModelKey(normalized, model)).orElse(Instant.EPOCH);
        return allModels.isAfter(specificModel) ? allModels : specificModel;
    }

    private String workloadModelKey(AiWorkload workload, String model) {
        AiWorkload normalized = workload == null ? AiWorkload.RAG_CHAT : workload.normalized();
        return normalized.name() + ":" + model;
    }

    private void waitForMinuteBudget(String keyPrefix, int cap, AiWorkload workload) {
        String key = keyPrefix + epochMinute();
        long current = parseLong(redisTemplate.opsForValue().get(key));
        if (current >= cap) {
            long waitMs = msUntilNextMinute();
            log.info("AI limiter minute wait workload={} waitMs={}", workload, waitMs);
            sleep(waitMs);
        }
    }

    private void checkHourBudget(String keyPrefix, int cap, AiWorkload workload) {
        String key = keyPrefix + epochHour();
        long current = parseLong(redisTemplate.opsForValue().get(key));
        if (current >= cap) {
            log.warn("429 received workload={} reason=total_hour_cap", workload);
            throw new AiRateLimitedException("AI provider rate limit exceeded. Please retry later.");
        }
    }

    private void incr(String key, long ttlSeconds) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (Long.valueOf(1L).equals(count)) {
            redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
        }
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private void sleep(long waitMs) {
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long epochMinute() {
        return System.currentTimeMillis() / 60_000;
    }

    private long epochHour() {
        return System.currentTimeMillis() / 3_600_000;
    }

    private long msUntilNextMinute() {
        long now = System.currentTimeMillis();
        return (now / 60_000 + 1) * 60_000 - now + 100;
    }
}
