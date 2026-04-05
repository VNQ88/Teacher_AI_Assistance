package com.example.teacherassistantai.dto.request;

import com.example.teacherassistantai.common.enumerate.DifficultyLevel;
import com.example.teacherassistantai.common.enumerate.QuestionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateQuestionRequest {
    @NotBlank(message = "Content is required")
    private String content;

    @NotNull(message = "Question type is required")
    private QuestionType questionType;

    private DifficultyLevel difficultyLevel;

    private String explanation;

    private String tags;
}

