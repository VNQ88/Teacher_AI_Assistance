package com.example.teacherassistantai.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AnswerOptionResponse {
    private Long id;
    private Long questionId;
    private String content;
    private Boolean isCorrect;
    private Integer orderIndex;
}

