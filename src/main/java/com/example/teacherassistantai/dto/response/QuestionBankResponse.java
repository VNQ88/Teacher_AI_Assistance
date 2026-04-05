package com.example.teacherassistantai.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionBankResponse {
    private Long id;
    private String title;
    private String description;
    private Long subjectId;
    private String subjectName;
    private Long sourceDocumentId;
    private Long createdById;
    private String createdByName;
    private Boolean published;
    private Integer questionCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
