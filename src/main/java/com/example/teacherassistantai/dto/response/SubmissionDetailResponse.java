package com.example.teacherassistantai.dto.response;

import com.example.teacherassistantai.common.enumerate.SubmissionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Kết quả bài thi chi tiết — dùng cho cả Student xem kết quả và Teacher xem submission.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmissionDetailResponse {
    private Long submissionId;
    private Long examId;
    private String examTitle;
    private Long studentId;
    private String studentName;
    private String studentEmail;
    private LocalDateTime startedAt;
    private LocalDateTime submittedAt;
    private Double totalScore;
    private Double passingScore;
    private Boolean passed;
    private SubmissionStatus status;
    private Integer gradedQuestions;    // Số câu đã chấm tự động
    private Integer pendingQuestions;   // Số câu chờ chấm (ESSAY, SHORT_ANSWER)
    private List<StudentAnswerDetailResponse> answers;
}

