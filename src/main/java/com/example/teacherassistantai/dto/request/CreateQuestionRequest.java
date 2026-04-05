package com.example.teacherassistantai.dto.request;

import com.example.teacherassistantai.common.enumerate.DifficultyLevel;
import com.example.teacherassistantai.common.enumerate.QuestionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CreateQuestionRequest {
    @NotBlank(message = "Content is required")
    private String content;

    @NotNull(message = "Question type is required")
    private QuestionType questionType;

    private DifficultyLevel difficultyLevel = DifficultyLevel.MEDIUM;

    private String explanation;

    private String tags;

    @NotEmpty(message = "At least one answer option is required")
    @Valid
    private List<CreateAnswerOptionRequest> answerOptions;
}

