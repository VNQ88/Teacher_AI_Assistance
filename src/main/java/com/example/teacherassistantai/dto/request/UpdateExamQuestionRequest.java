package com.example.teacherassistantai.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class UpdateExamQuestionRequest {

    @Min(value = 0, message = "Order index must be non-negative")
    private Integer orderIndex;

    @DecimalMin(value = "0.0", inclusive = false, message = "Score must be positive")
    private Double score;
}

