package com.example.teacherassistantai.dto.response;

import com.example.teacherassistantai.common.enumerate.ExamStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamResponse {
    private Long id;
    private String title;
    private String description;
    private Long classroomId;
    private String classroomName;
    private Long subjectId;
    private String subjectName;
    private Long createdById;
    private String createdByName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationMinutes;
    private Double totalScore;
    private Double passingScore;
    private Boolean shuffleQuestions;
    private Boolean shuffleOptions;
    private ExamStatus status;
    private Integer questionCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

