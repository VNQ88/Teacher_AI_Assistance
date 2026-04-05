package com.example.teacherassistantai.entity;

import com.example.teacherassistantai.common.enumerate.AgentType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

/**
 * Log hoạt động của từng AI agent — phục vụ audit, debug và theo dõi chi phí.
 *
 * Dùng polymorphic reference (relatedEntityType + relatedEntityId) để liên kết
 * với bất kỳ entity nào mà không cần foreign key cứng.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "agent_logs", indexes = {
        @Index(name = "idx_agent_log_type", columnList = "agent_type"),
        @Index(name = "idx_agent_log_entity", columnList = "related_entity_type, related_entity_id")
})
public class AgentLog extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    AgentType agentType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "triggered_by")
    User triggeredBy;

    /**
     * Môn học liên quan (nullable).
     * Hữu ích để thống kê agent nào được dùng nhiều cho môn nào.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subject_id")
    Subject subject;

    @Column(columnDefinition = "TEXT")
    String inputSummary; // Tóm tắt input gửi vào agent

    @Column(columnDefinition = "TEXT")
    String outputSummary; // Tóm tắt output nhận được

    @Column(nullable = false)
    @Builder.Default
    Boolean success = true;

    @Column(columnDefinition = "TEXT")
    String errorMessage; // Chi tiết lỗi nếu success = false

    @Column
    Long processingTimeMs;

    @Column
    Integer tokensUsed; // Số token API tiêu thụ

    /**
     * Tên class entity liên quan.
     * Ví dụ: "Document", "Exam", "ChatSession"
     */
    @Column(length = 100)
    String relatedEntityType;

    /**
     * ID của entity liên quan.
     */
    @Column
    Long relatedEntityId;
}
