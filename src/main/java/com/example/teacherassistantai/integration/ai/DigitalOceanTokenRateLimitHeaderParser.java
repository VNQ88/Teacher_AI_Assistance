package com.example.teacherassistantai.integration.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
public class DigitalOceanTokenRateLimitHeaderParser {

    private static final String LIMIT_TOKENS_PER_DAY = "x-ratelimit-limit-tokens-per-day";
    private static final String REMAINING_TOKENS_PER_DAY = "x-ratelimit-remaining-tokens-per-day";
    private static final String LIMIT_TOKENS_PER_MINUTE = "x-ratelimit-limit-tokens-per-minute";
    private static final String REMAINING_TOKENS_PER_MINUTE = "x-ratelimit-remaining-tokens-per-minute";

    public AiRateLimitSnapshot parse(HttpHeaders headers) {
        if (headers == null || headers.isEmpty()) {
            return new AiRateLimitSnapshot(null, null, null, null, Instant.now());
        }
        return new AiRateLimitSnapshot(
                parseLong(headers, LIMIT_TOKENS_PER_DAY),
                parseLong(headers, REMAINING_TOKENS_PER_DAY),
                parseLong(headers, LIMIT_TOKENS_PER_MINUTE),
                parseLong(headers, REMAINING_TOKENS_PER_MINUTE),
                Instant.now()
        );
    }

    private Long parseLong(HttpHeaders headers, String name) {
        String value = headers.getFirst(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            log.debug("Could not parse DigitalOcean token rate limit header {}={}", name, value);
            return null;
        }
    }
}
