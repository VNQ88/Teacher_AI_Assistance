package com.example.teacherassistantai.common.enumerate;

public enum AgentType {
    QUIZ_GENERATOR,   // Tạo câu hỏi từ tài liệu
    GRADER,           // Chấm thi tự động
    RAG_RETRIEVER,    // Trích xuất kiến thức từ tài liệu
    SYSTEM_CHATBOT,   // Chatbot hỗ trợ thông tin hệ thống
    KNOWLEDGE_CHATBOT // Chatbot hỏi-đáp kiến thức môn học
}
