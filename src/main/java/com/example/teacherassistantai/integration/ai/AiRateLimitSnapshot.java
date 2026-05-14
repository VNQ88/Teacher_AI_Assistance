package com.example.teacherassistantai.integration.ai;

import java.time.Instant;

public record AiRateLimitSnapshot(
        Long limitTokensPerDay,
        Long remainingTokensPerDay,
        Long limitTokensPerMinute,
        Long remainingTokensPerMinute,
        Instant observedAt
) {
}
