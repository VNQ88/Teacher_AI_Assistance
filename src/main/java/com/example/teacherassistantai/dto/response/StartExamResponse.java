package com.example.teacherassistantai.dto.response;

import com.example.teacherassistantai.common.enumerate.SubmissionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response trả về sau khi Student bắt đầu làm bài (POST /exams/{id}/start).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartExamResponse {
    private Long submissionId;
    private Long examId;
    private String examTitle;
    private LocalDateTime startedAt;
    private LocalDateTime deadlineAt;   // startedAt + durationMinutes
    private SubmissionStatus status;
    private List<ExamQuestionForStudentResponse> questions;
}

