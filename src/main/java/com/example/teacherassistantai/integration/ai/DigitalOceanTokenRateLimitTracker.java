package com.example.teacherassistantai.integration.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Slf4j
@Component
public class DigitalOceanTokenRateLimitTracker {

    private static final String SNAPSHOT_PREFIX = "ai:token-rl:";
    private static final String PAUSE_PREFIX = "ai:token-rl:pause:";
    private static final Duration SNAPSHOT_TTL = Duration.ofHours(26);

    private final RedisTemplate<String, String> redisTemplate;

    public DigitalOceanTokenRateLimitTracker(
            @Qualifier("aiRateLimitRedisTemplate") RedisTemplate<String, String> redisTemplate
    ) {
        this.redisTemplate = redisTemplate;
    }

    public void update(String accountAlias, String model, AiRateLimitSnapshot snapshot) {
        if (snapshot == null || accountAlias == null || model == null) {
            return;
        }
        redisTemplate.opsForValue().set(snapshotKey(accountAlias, model), serialize(snapshot), SNAPSHOT_TTL);
    }

    public Optional<AiRateLimitSnapshot> getSnapshot(String accountAlias, String model) {
        String value = redisTemplate.opsForValue().get(snapshotKey(accountAlias, model));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(deserialize(value));
        } catch (RuntimeException ex) {
            log.debug("Could not parse token rate limit snapshot accountAlias={} model={}", accountAlias, model);
            return Optional.empty();
        }
    }

    public void pause(String accountAlias, String model, Instant until) {
        if (accountAlias == null || model == null || until == null) {
            return;
        }
        long ttlSeconds = Math.max(60, until.getEpochSecond() - Instant.now().getEpochSecond() + 10);
        redisTemplate.opsForValue().set(pauseKey(accountAlias, model),
                String.valueOf(until.getEpochSecond()), Duration.ofSeconds(ttlSeconds));
    }

    public Optional<Instant> pausedUntil(String accountAlias, String model) {
        String value = redisTemplate.opsForValue().get(pauseKey(accountAlias, model));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            Instant pausedUntil = Instant.ofEpochSecond(Long.parseLong(value));
            return pausedUntil.isAfter(Instant.now()) ? Optional.of(pausedUntil) : Optional.empty();
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private String snapshotKey(String accountAlias, String model) {
        return SNAPSHOT_PREFIX + accountAlias + ":" + model + ":snapshot";
    }

    private String pauseKey(String accountAlias, String model) {
        return PAUSE_PREFIX + accountAlias + ":" + model + ":pausedUntil";
    }

    private String serialize(AiRateLimitSnapshot snapshot) {
        return value(snapshot.limitTokensPerDay())
                + "|" + value(snapshot.remainingTokensPerDay())
                + "|" + value(snapshot.limitTokensPerMinute())
                + "|" + value(snapshot.remainingTokensPerMinute())
                + "|" + (snapshot.observedAt() == null ? "" : snapshot.observedAt().toEpochMilli());
    }

    private AiRateLimitSnapshot deserialize(String value) {
        String[] parts = value.split("\\|", -1);
        return new AiRateLimitSnapshot(
                longValue(parts, 0),
                longValue(parts, 1),
                longValue(parts, 2),
                longValue(parts, 3),
                instantValue(parts, 4)
        );
    }

    private String value(Long value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Long longValue(String[] parts, int index) {
        if (index >= parts.length || parts[index].isBlank()) {
            return null;
        }
        return Long.parseLong(parts[index]);
    }

    private Instant instantValue(String[] parts, int index) {
        if (index >= parts.length || parts[index].isBlank()) {
            return Instant.now();
        }
        return Instant.ofEpochMilli(Long.parseLong(parts[index]));
    }
}
