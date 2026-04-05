package com.example.teacherassistantai.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UpdateExamRequest {

    @Size(max = 255, message = "Title must not exceed 255 characters")
    private String title;

    private String description;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    @Min(value = 1, message = "Duration must be at least 1 minute")
    private Integer durationMinutes;

    @DecimalMin(value = "0.0", inclusive = false, message = "Total score must be positive")
    private Double totalScore;

    @DecimalMin(value = "0.0", message = "Passing score must be non-negative")
    private Double passingScore;

    private Boolean shuffleQuestions;

    private Boolean shuffleOptions;
}

