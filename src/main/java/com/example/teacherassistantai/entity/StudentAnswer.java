package com.example.teacherassistantai.entity;

import com.example.teacherassistantai.common.enumerate.GradingStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

/**
 * Câu trả lời của học sinh cho từng câu hỏi trong bài thi.
 *
 * Hỗ trợ nhiều loại câu hỏi:
 * - MCQ/TRUE_FALSE: lưu selectedOption
 * - MULTI_SELECT: lưu selectedOptionIds (JSON array)
 * - SHORT_ANSWER/FILL_IN_BLANK/ESSAY: lưu answerContent
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "student_answers")
public class StudentAnswer extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id", nullable = false)
    ExamSubmission submission;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_question_id", nullable = false)
    ExamQuestion examQuestion;

    /**
     * Đáp án dạng text (SHORT_ANSWER, ESSAY, FILL_IN_BLANK).
     */
    @Column(columnDefinition = "TEXT")
    String answerContent;

    /**
     * Đáp án được chọn (MULTIPLE_CHOICE, TRUE_FALSE).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "selected_option_id")
    AnswerOption selectedOption;

    /**
     * JSON array chứa IDs của các option được chọn (MULTI_SELECT).
     * Ví dụ: "[1, 3, 5]"
     */
    @Column(columnDefinition = "TEXT")
    String selectedOptionIds;

    @Column
    @Builder.Default
    Double score = 0.0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    GradingStatus gradingStatus = GradingStatus.PENDING;

    /**
     * Phản hồi tự động từ AI agent (đặc biệt hữu ích với essay/short-answer).
     * Ví dụ Tiếng Anh: "Câu trả lời thiếu mạo từ 'the' trước danh từ số ít..."
     */
    @Column(columnDefinition = "TEXT")
    String aiFeedback;

    /**
     * Nhận xét của giáo viên sau khi review kết quả AI chấm.
     */
    @Column(columnDefinition = "TEXT")
    String teacherFeedback;
}
