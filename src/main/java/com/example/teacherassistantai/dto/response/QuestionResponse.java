package com.example.teacherassistantai.dto.response;

import com.example.teacherassistantai.common.enumerate.DifficultyLevel;
import com.example.teacherassistantai.common.enumerate.QuestionType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class QuestionResponse {
    private Long id;
    private Long questionBankId;
    private String content;
    private QuestionType questionType;
    private DifficultyLevel difficultyLevel;
    private String explanation;
    private String tags;
    private Boolean isAiGenerated;
    private Long sourceChunkId;
    private List<AnswerOptionResponse> answerOptions;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

