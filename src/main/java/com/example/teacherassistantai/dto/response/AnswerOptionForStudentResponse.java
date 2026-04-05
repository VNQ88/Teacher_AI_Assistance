package com.example.teacherassistantai.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AnswerOption trả về cho Student khi làm bài — KHÔNG có isCorrect.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnswerOptionForStudentResponse {
    private Long id;
    private String content;
    private Integer orderIndex;
}

