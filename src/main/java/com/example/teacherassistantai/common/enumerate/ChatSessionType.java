package com.example.teacherassistantai.common.enumerate;

public enum ChatSessionType {
    /**
     * Chatbot hỗ trợ hệ thống: trả lời câu hỏi về điểm thi, lịch thi, thông tin lớp học, ...
     */
    SYSTEM_SUPPORT,

    /**
     * Hỏi-đáp kiến thức môn học: học sinh hỏi về bài giảng, từ vựng, ngữ pháp, ...
     * Agent sẽ dùng RAG để trích xuất kiến thức từ tài liệu của môn học tương ứng.
     */
    KNOWLEDGE_QA
}
