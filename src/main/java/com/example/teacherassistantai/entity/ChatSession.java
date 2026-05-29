package com.example.teacherassistantai.entity;

import com.example.teacherassistantai.common.enumerate.ChatSessionType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.ArrayList;
import java.util.List;

/**
 * Phiên hội thoại với AI agent.
 *
 * Hai loại phiên:
 * - SYSTEM_SUPPORT: hỏi về điểm thi, lịch thi, thông tin lớp học → không cần Subject
 * - KNOWLEDGE_QA: hỏi kiến thức môn học → bắt buộc có Subject để RAG filter đúng tài liệu
 *
 * Thiết kế cho multi-subject:
 * Mỗi phiên KNOWLEDGE_QA gắn với 1 Subject cụ thể.
 * → RAG agent chỉ tìm kiếm trong DocumentChunk có subjectId tương ứng.
 * → Học sinh hỏi về Tiếng Anh sẽ không nhận được nội dung từ tài liệu Toán.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "chat_sessions")
public class ChatSession extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    ChatSessionType sessionType;

    /**
     * Môn học của phiên hội thoại (chỉ bắt buộc với KNOWLEDGE_QA).
     * Null với SYSTEM_SUPPORT.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subject_id")
    Subject subject;

    @Column(length = 255)
    String title; // Tiêu đề tóm tắt phiên (có thể do AI tạo từ câu hỏi đầu tiên)

    @Column(nullable = false)
    @Builder.Default
    Boolean active = true;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    List<ChatMessage> messages = new ArrayList<>();
}
