package com.example.teacherassistantai.common.enumerate;

public enum DocumentStatus {
    UPLOADED,     // File đã có trên object storage
    PARSING,      // Đang parse nội dung thô
    CHUNKING,     // Đang chia chunk
    EMBEDDING,    // Đang tạo embedding
    READY,        // Sẵn sàng hỏi đáp RAG
    FULL_USE,     // Sẵn sàng đầy đủ summary/câu hỏi
    FAILED        // Xử lý lỗi
}
