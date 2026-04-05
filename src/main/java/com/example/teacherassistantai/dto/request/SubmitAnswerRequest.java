package com.example.teacherassistantai.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * Câu trả lời cho một câu hỏi trong bài thi.
 * - MCQ / TRUE_FALSE  → selectedOptionId
 * - MULTI_SELECT      → selectedOptionIds
 * - SHORT_ANSWER / ESSAY / FILL_IN_BLANK → answerContent
 */
@Data
public class SubmitAnswerRequest {

    @NotNull(message = "examQuestionId is required")
    private Long examQuestionId;

    // MULTIPLE_CHOICE, TRUE_FALSE
    private Long selectedOptionId;

    // MULTI_SELECT
    private List<Long> selectedOptionIds;

    // SHORT_ANSWER, ESSAY, FILL_IN_BLANK
    private String answerContent;
}

