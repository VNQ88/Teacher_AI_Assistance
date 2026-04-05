package com.example.teacherassistantai.dto.response;

import com.example.teacherassistantai.common.enumerate.ExamStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ExamResponse dành cho Student — bổ sung mySubmissionStatus.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentExamResponse {
    private Long id;
    private String title;
    private String description;
    private Long classroomId;
    private String classroomName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationMinutes;
    private Double totalScore;
    private Double passingScore;
    private ExamStatus status;
    private Integer questionCount;
    // Trạng thái bài làm của chính student: NOT_STARTED | IN_PROGRESS | SUBMITTED | ...
    private String mySubmissionStatus;
}

