package com.example.teacherassistantai.dto.response;

import com.example.teacherassistantai.common.enumerate.AgentType;
import com.example.teacherassistantai.common.enumerate.MessageRole;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ChatMessageResponse {
    private Long id;
    private MessageRole role;
    private String content;
    private AgentType agentType;
    private Double confidenceScore;
    private String confidenceLevel;
    private Integer tokensUsed;
    private Long responseTimeMs;
    private List<RagSourceResponse> sources;
    private LocalDateTime createdAt;
}

