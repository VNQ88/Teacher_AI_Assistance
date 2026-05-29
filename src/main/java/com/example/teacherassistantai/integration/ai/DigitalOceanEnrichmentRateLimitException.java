package com.example.teacherassistantai.integration.ai;

public class DigitalOceanEnrichmentRateLimitException extends RuntimeException {

    private final AiRateLimitSnapshot snapshot;

    public DigitalOceanEnrichmentRateLimitException(AiRateLimitSnapshot snapshot, Throwable cause) {
        super("DigitalOcean enrichment rate limit exceeded", cause);
        this.snapshot = snapshot;
    }

    public AiRateLimitSnapshot snapshot() {
        return snapshot;
    }
}
