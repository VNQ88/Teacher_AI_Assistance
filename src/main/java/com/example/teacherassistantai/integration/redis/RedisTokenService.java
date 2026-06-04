package com.example.teacherassistantai.integration.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RedisTokenService {
    private static final String USER_TOKEN_INVALID_AFTER_PREFIX = "UserTokenInvalidAfter:";

    private final RedisTokenRepository redisTokenRepository;
    private final RedisTemplate<String, RedisToken> redisTokenRedisTemplate;
    private final RedisTemplate<String, String> stringRedisTemplate; // Added for String values

    public void save(RedisToken token) {
        redisTokenRepository.save(token);
        // Set expiration time for the token in Redis
        long ttlSeconds = ttlSecondsUntil(token.getExpireTime());
        if (ttlSeconds > 0) {
            redisTokenRedisTemplate.expire("RedisToken:" + token.getId(), ttlSeconds, TimeUnit.SECONDS);
            // Use stringRedisTemplate for refreshToken key
            if (token.getRefreshToken() != null) {
                stringRedisTemplate.opsForValue().set("RefreshToken:" + token.getRefreshToken(), "revoked", ttlSeconds, TimeUnit.SECONDS);
            }
        }
    }

    public boolean isExists(String id) {
        return redisTokenRepository.existsById(id);
    }

    // Check refreshToken in Redis
    public boolean isRefreshTokenRevoked(String refreshToken) {
        return stringRedisTemplate.hasKey("RefreshToken:" + refreshToken); // Use stringRedisTemplate
    }

    public boolean revokeRefreshTokenIfUnused(String refreshToken, Date expireTime) {
        long ttlSeconds = ttlSecondsUntil(expireTime);
        if (ttlSeconds <= 0) {
            return false;
        }

        Boolean saved = stringRedisTemplate.opsForValue()
                .setIfAbsent("RefreshToken:" + refreshToken, "revoked", ttlSeconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(saved);
    }

    public void revokeAllUserTokens(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("User id must not be null");
        }
        stringRedisTemplate.opsForValue()
                .set(USER_TOKEN_INVALID_AFTER_PREFIX + userId, String.valueOf(System.currentTimeMillis()));
    }

    public boolean isTokenRevokedByUserInvalidation(Long userId, Date issuedAt) {
        if (userId == null || issuedAt == null) {
            return false;
        }

        String invalidAfter = stringRedisTemplate.opsForValue()
                .get(USER_TOKEN_INVALID_AFTER_PREFIX + userId);
        if (invalidAfter == null || invalidAfter.isBlank()) {
            return false;
        }

        Date invalidAfterDate = new Date(Long.parseLong(invalidAfter));
        return !issuedAt.after(invalidAfterDate);
    }

    private long ttlSecondsUntil(Date expireTime) {
        return expireTime != null ?
                LocalDateTime.now().until(expireTime.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime(), java.time.temporal.ChronoUnit.SECONDS) :
                0;
    }
}
