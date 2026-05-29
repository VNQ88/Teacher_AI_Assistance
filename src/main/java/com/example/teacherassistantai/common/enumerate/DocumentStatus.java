package com.example.teacherassistantai.common.enumerate;

public enum DocumentStatus {
    UPLOADED,
    PARSING,
    CHUNKING,
    EMBEDDING,
    SUMMARISING,  // blocking — summary enrichment đang chạy
    READY,        // summaries done, user có thể chat
    FAILED
}
