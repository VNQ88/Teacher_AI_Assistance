package com.example.teacherassistantai.integration.ai;

public enum AiWorkload {
    RAG_CHAT,
    EMBEDDING,
    ENRICH_SUMMARY,
    ENRICH_REVIEW_QUESTION,

    @Deprecated
    INTERACTIVE,

    @Deprecated
    BACKGROUND;

    public AiWorkload normalized() {
        return switch (this) {
            case INTERACTIVE -> RAG_CHAT;
            case BACKGROUND -> ENRICH_REVIEW_QUESTION;
            default -> this;
        };
    }

    public boolean enrichment() {
        AiWorkload normalized = normalized();
        return normalized == ENRICH_SUMMARY || normalized == ENRICH_REVIEW_QUESTION;
    }
}
