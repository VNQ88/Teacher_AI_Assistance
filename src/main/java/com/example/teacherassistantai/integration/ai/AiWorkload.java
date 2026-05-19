package com.example.teacherassistantai.integration.ai;

public enum AiWorkload {
    RAG_CHAT,
    EMBEDDING,
    ENRICH_SUMMARY,
    ENRICH_REVIEW_QUESTION,
    /** On-demand quiz generation: uses enrichment client (120s timeout) but RAG_CHAT rate limit bucket (180/min). */
    ENRICH_REVIEW_QUESTION_ONDEMAND,

    @Deprecated
    INTERACTIVE,

    @Deprecated
    BACKGROUND;

    public AiWorkload normalized() {
        return switch (this) {
            case INTERACTIVE -> RAG_CHAT;
            case BACKGROUND -> ENRICH_REVIEW_QUESTION;
            case ENRICH_REVIEW_QUESTION_ONDEMAND -> RAG_CHAT;
            default -> this;
        };
    }

    public boolean enrichment() {
        AiWorkload normalized = normalized();
        return normalized == ENRICH_SUMMARY || normalized == ENRICH_REVIEW_QUESTION;
    }
}
