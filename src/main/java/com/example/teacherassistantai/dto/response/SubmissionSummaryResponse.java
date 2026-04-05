package com.example.teacherassistantai.dto.response;

import com.example.teacherassistantai.common.enumerate.SubmissionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Tóm tắt một submission — dùng trong danh sách submissions của Exam (Teacher view).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmissionSummaryResponse {
    private Long id;
    private Long examId;
    private String examTitle;
    private Long studentId;
    private String studentName;
    private String studentEmail;
    private LocalDateTime startedAt;
    private LocalDateTime submittedAt;
    private Double totalScore;
    private SubmissionStatus status;
}

