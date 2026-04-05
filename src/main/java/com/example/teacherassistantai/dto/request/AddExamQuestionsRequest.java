package com.example.teacherassistantai.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

/**
 * Dùng cho cả single (1 questionId) và bulk (nhiều questionIds).
 * Single: questionIds = [id]
 * Bulk:   questionIds = [id1, id2, id3, ...]
 */
@Data
public class AddExamQuestionsRequest {

    @NotNull(message = "Question IDs are required")
    @Size(min = 1, message = "At least one question must be provided")
    private List<@NotNull Long> questionIds;

    @DecimalMin(value = "0.0", inclusive = false, message = "Score must be positive")
    private Double scorePerQuestion = 1.0;
}

