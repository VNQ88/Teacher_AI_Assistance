package com.example.teacherassistantai.integration.ai;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.exception.AiRateLimitedException;
import com.example.teacherassistantai.exception.BackgroundRateLimitedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class DigitalOceanAiRateLimiter {

    private static final String KEY_BG_PAUSED = "ai:bg:pausedUntil";
    private static final String PREFIX_TOTAL_MIN = "ai:rl:total:min:";
    private static final String PREFIX_BG_MIN = "ai:rl:bg:min:";
    private static final String PREFIX_TOTAL_HOUR = "ai:rl:total:hour:";
    private static final String PREFIX_BG_HOUR = "ai:rl:bg:hour:";

    private final RedisTemplate<String, String> redisTemplate;
    private final RateLimitTracker rateLimitTracker;
    private final RagProperties ragProperties;

    public void acquire(AiWorkload workload) {
        RagProperties.Ai.RateLimit cfg = ragProperties.getAi().getRateLimit();
        if (!cfg.isEnabled()) return;

        if (workload == AiWorkload.BACKGROUND) {
            checkBackgroundNotPaused();
            checkDoRemainingThreshold(cfg);
            waitForMinuteBudget(PREFIX_BG_MIN, cfg.getBackgroundRequestsPerMinute(), workload);
            checkHourBudget(PREFIX_BG_HOUR, cfg.getBackgroundRequestsPerHour(), workload, cfg);
        }

        waitForMinuteBudget(PREFIX_TOTAL_MIN, cfg.getTotalRequestsPerMinute(), workload);
        checkHourBudget(PREFIX_TOTAL_HOUR, cfg.getTotalRequestsPerHour(), workload, cfg);

        log.debug("AI limiter acquire workload={}", workload);
        incr(PREFIX_TOTAL_MIN + epochMinute(), 90);
        incr(PREFIX_TOTAL_HOUR + epochHour(), 3700);
        if (workload == AiWorkload.BACKGROUND) {
            incr(PREFIX_BG_MIN + epochMinute(), 90);
            incr(PREFIX_BG_HOUR + epochHour(), 3700);
        }
    }

    public boolean isBackgroundPaused() {
        String val = redisTemplate.opsForValue().get(KEY_BG_PAUSED);
        if (val == null) return false;
        return Instant.ofEpochSecond(Long.parseLong(val)).isAfter(Instant.now());
    }

    // Called by gateway on 429 response; sets pause state and throws for background workload
    public void handleBackground429() {
        RagProperties.Ai.RateLimit cfg = ragProperties.getAi().getRateLimit();
        Instant until = computePausedUntil(cfg);
        setPausedUntil(until);
        log.info("Background paused until={} reason=429_received", until);
        throw new BackgroundRateLimitedException(until);
    }

    private void checkBackgroundNotPaused() {
        String val = redisTemplate.opsForValue().get(KEY_BG_PAUSED);
        if (val == null) return;
        Instant pausedUntil = Instant.ofEpochSecond(Long.parseLong(val));
        if (pausedUntil.isAfter(Instant.now())) {
            throw new BackgroundRateLimitedException(pausedUntil);
        }
    }

    private void checkDoRemainingThreshold(RagProperties.Ai.RateLimit cfg) {
        int remaining = rateLimitTracker.getRemaining();
        if (remaining < cfg.getBackgroundPauseRemainingThreshold()) {
            Instant until = computePausedUntil(cfg);
            setPausedUntil(until);
            log.info("Background paused until={} reason=DO_remaining_threshold remaining={}", until, remaining);
            throw new BackgroundRateLimitedException(until);
        }
    }

    private void waitForMinuteBudget(String keyPrefix, int cap, AiWorkload workload) {
        String key = keyPrefix + epochMinute();
        String val = redisTemplate.opsForValue().get(key);
        long current = val != null ? Long.parseLong(val) : 0;
        if (current >= cap) {
            long waitMs = msUntilNextMinute();
            log.info("AI limiter minute wait workload={} waitMs={}", workload, waitMs);
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void checkHourBudget(String keyPrefix, int cap, AiWorkload workload, RagProperties.Ai.RateLimit cfg) {
        String key = keyPrefix + epochHour();
        String val = redisTemplate.opsForValue().get(key);
        long current = val != null ? Long.parseLong(val) : 0;
        if (current < cap) return;

        if (workload == AiWorkload.BACKGROUND) {
            Instant until = computePausedUntil(cfg);
            setPausedUntil(until);
            log.info("Background paused until={} reason=hour_cap", until);
            throw new BackgroundRateLimitedException(until);
        } else {
            log.warn("429 received workload={} reason=total_hour_cap", workload);
            throw new AiRateLimitedException("AI provider rate limit exceeded. Please retry later.");
        }
    }

    private Instant computePausedUntil(RagProperties.Ai.RateLimit cfg) {
        Instant resetAt = rateLimitTracker.getResetAt();
        Instant now = Instant.now();
        Instant fallback = now.plusSeconds((long) cfg.getBackgroundHourlyPauseMinutes() * 60);
        return (resetAt != null && resetAt.isAfter(now)) ? resetAt : fallback;
    }

    private void setPausedUntil(Instant until) {
        long ttlSeconds = Math.max(60, until.getEpochSecond() - Instant.now().getEpochSecond() + 10);
        redisTemplate.opsForValue().set(KEY_BG_PAUSED, String.valueOf(until.getEpochSecond()),
                Duration.ofSeconds(ttlSeconds));
    }

    private void incr(String key, long ttlSeconds) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (Long.valueOf(1L).equals(count)) {
            redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
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
