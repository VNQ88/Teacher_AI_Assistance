package com.example.teacherassistantai.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

/**
 * Bảng trung gian Exam ↔ Question.
 * Cho phép cùng 1 Question xuất hiện ở nhiều Exam với điểm số khác nhau.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "exam_questions", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"exam_id", "question_id"})
})
public class ExamQuestion extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_id", nullable = false)
    Exam exam;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    Question question;

    @Column(nullable = false)
    Integer orderIndex; // Thứ tự hiển thị trong đề thi

    @Column(nullable = false)
    @Builder.Default
    Double score = 1.0; // Điểm tối đa của câu hỏi này trong đề thi
}
