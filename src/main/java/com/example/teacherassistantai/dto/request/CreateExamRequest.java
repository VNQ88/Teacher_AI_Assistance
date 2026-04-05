package com.example.teacherassistantai.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreateExamRequest {

    @NotBlank(message = "Title is required")
    @Size(max = 255, message = "Title must not exceed 255 characters")
    private String title;

    private String description;

    @NotNull(message = "Classroom ID is required")
    private Long classroomId;

    @NotNull(message = "Start time is required")
    @Future(message = "Start time must be in the future")
    private LocalDateTime startTime;

    @NotNull(message = "End time is required")
    private LocalDateTime endTime;

    @NotNull(message = "Duration is required")
    @Min(value = 1, message = "Duration must be at least 1 minute")
    private Integer durationMinutes;

    @DecimalMin(value = "0.0", inclusive = false, message = "Total score must be positive")
    private Double totalScore = 10.0;

    @DecimalMin(value = "0.0", message = "Passing score must be non-negative")
    private Double passingScore = 5.0;

    private Boolean shuffleQuestions = false;

    private Boolean shuffleOptions = false;
}

