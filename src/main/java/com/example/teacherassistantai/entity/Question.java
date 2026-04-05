package com.example.teacherassistantai.entity;

import com.example.teacherassistantai.common.enumerate.DifficultyLevel;
import com.example.teacherassistantai.common.enumerate.QuestionType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.ArrayList;
import java.util.List;

/**
 * Câu hỏi trong bộ ngân hàng câu hỏi.
 * Hỗ trợ nhiều loại câu hỏi để phù hợp với nhiều môn học khác nhau.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "questions")
public class Question extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_bank_id", nullable = false)
    QuestionBank questionBank;

    @Column(columnDefinition = "TEXT", nullable = false)
    String content; // Nội dung câu hỏi

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    QuestionType questionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    DifficultyLevel difficultyLevel = DifficultyLevel.MEDIUM;

    /**
     * Giải thích đáp án đúng — dùng để học sinh học sau khi làm bài.
     * Đặc biệt hữu ích cho môn Tiếng Anh (giải thích ngữ pháp, từ vựng).
     */
    @Column(columnDefinition = "TEXT")
    String explanation;

    /**
     * Tags phân loại câu hỏi theo chủ đề/skill (dạng JSON array).
     * Ví dụ Tiếng Anh: ["grammar", "past-tense"]
     * Ví dụ Toán: ["algebra", "quadratic-equation"]
     */
    @Column(columnDefinition = "TEXT")
    String tags;

    @Column(nullable = false)
    @Builder.Default
    Boolean isAiGenerated = false;

    /**
     * ID của DocumentChunk dùng để tạo câu hỏi này (nếu do AI tạo).
     * Dùng để truy vết nguồn gốc câu hỏi.
     */
    @Column
    Long sourceChunkId;

    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    List<AnswerOption> answerOptions = new ArrayList<>();
}
