package com.example.teacherassistantai.integration.ai;

public record AiChatCompletion(
        String content,
        AiUsage usage,
        AiRateLimitSnapshot rateLimit,
        String model,
        AiWorkload workload
) {
}
