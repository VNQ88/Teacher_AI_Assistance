package com.example.teacherassistantai.integration.ai;

public record AiModelRoute(
        AiWorkload workload,
        String accountAlias,
        String baseUrl,
        String apiKey,
        String model,
        boolean enrichment
) {
}
