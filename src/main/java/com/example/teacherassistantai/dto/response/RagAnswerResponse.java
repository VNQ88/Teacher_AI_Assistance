package com.example.teacherassistantai.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class RagAnswerResponse {
    private Long sessionId;
    private Long messageId;
    private String answer;
    private Double confidenceScore;
    private String confidenceLevel;
    private Boolean fallback;
    private String lowConfidenceReason;
    private List<String> suggestions;
    private List<RagSourceResponse> sources;
    private TokenUsageResponse usage;
    private LocalDateTime createdAt;
}

