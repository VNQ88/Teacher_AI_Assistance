package com.example.teacherassistantai.entity;

import com.example.teacherassistantai.common.enumerate.AgentType;
import com.example.teacherassistantai.common.enumerate.MessageRole;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.ArrayList;
import java.util.List;

/**
 * Tin nhắn trong một phiên hội thoại.
 *
 * sourceChunks: các DocumentChunk được RAG agent dùng để tạo câu trả lời.
 * → Cho phép hiển thị "nguồn tham khảo" từ tài liệu chính thống của trường.
 * → Học sinh/giáo viên có thể kiểm tra độ chính xác của câu trả lời.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "chat_messages")
public class ChatMessage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    ChatSession session;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    MessageRole role; // USER hoặc ASSISTANT

    @Column(columnDefinition = "TEXT", nullable = false)
    String content;

    /**
     * Agent nào đã xử lý và tạo ra tin nhắn này (null nếu role = USER).
     */
    @Enumerated(EnumType.STRING)
    @Column
    AgentType agentType;

    /**
     * Các đoạn tài liệu được dùng làm ngữ cảnh cho RAG agent.
     * Hiển thị cho người dùng như "Trích từ: [Tên tài liệu] - trang X"
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "message_source_chunks",
            joinColumns = @JoinColumn(name = "message_id"),
            inverseJoinColumns = @JoinColumn(name = "chunk_id")
    )
    @Builder.Default
    List<DocumentChunk> sourceChunks = new ArrayList<>();

    /**
     * Số token tiêu thụ (để theo dõi chi phí API AI).
     */
    @Column
    Integer tokensUsed;

    /**
     * Thời gian phản hồi của AI (ms).
     */
    @Column
    Long responseTimeMs;
}
