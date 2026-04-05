package com.example.teacherassistantai.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TokenUsageResponse {
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Long latencyMs;
}

