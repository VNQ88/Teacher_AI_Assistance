package com.example.teacherassistantai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateAnswerOptionRequest {
    @NotBlank(message = "Content is required")
    private String content;

    @NotNull(message = "isCorrect is required")
    private Boolean isCorrect;

    private Integer orderIndex;
}

