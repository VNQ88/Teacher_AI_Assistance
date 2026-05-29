package com.example.teacherassistantai.integration.ai;

public record AiUsage(
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {
}
