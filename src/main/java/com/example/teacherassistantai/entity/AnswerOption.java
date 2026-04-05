package com.example.teacherassistantai.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

/**
 * Lựa chọn đáp án cho câu hỏi trắc nghiệm (MULTIPLE_CHOICE, MULTI_SELECT, TRUE_FALSE).
 * Với SHORT_ANSWER / ESSAY: tạo 1 bản ghi duy nhất chứa đáp án mẫu (isCorrect = true).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "answer_options")
public class AnswerOption extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    Question question;

    @Column(columnDefinition = "TEXT", nullable = false)
    String content;

    @Column(nullable = false)
    @Builder.Default
    Boolean isCorrect = false;

    @Column
    Integer orderIndex; // Thứ tự hiển thị (A, B, C, D)
}
