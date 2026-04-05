package com.example.teacherassistantai.integration.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RedisTokenService {
    private final RedisTokenRepository redisTokenRepository;
    private final RedisTemplate<String, RedisToken> redisTokenRedisTemplate;
    private final RedisTemplate<String, String> stringRedisTemplate; // Added for String values

    public void save(RedisToken token) {
        redisTokenRepository.save(token);
        // Set expiration time for the token in Redis
        long ttlSeconds = token.getExpireTime() != null ?
                LocalDateTime.now().until(token.getExpireTime().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime(), java.time.temporal.ChronoUnit.SECONDS) :
                0;
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
}