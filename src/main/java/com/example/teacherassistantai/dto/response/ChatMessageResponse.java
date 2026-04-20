package com.example.teacherassistantai.dto.response;

import com.example.teacherassistantai.common.enumerate.AgentType;
import com.example.teacherassistantai.common.enumerate.MessageRole;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@Schema(description = "Normalized chat message model used by both send-message and history endpoints")
public class ChatMessageResponse {
    @Schema(description = "Message id", example = "125")
    private Long id;

    @Schema(description = "Author role of the message", example = "ASSISTANT")
    private MessageRole role;

    @Schema(description = "Message text content", example = "De bai nay can ap dung dinh luat bao toan dong luong.")
    private String content;

    @Schema(description = "Agent that generated this message; null for user messages", example = "KNOWLEDGE_CHATBOT")
    private AgentType agentType;

    @Schema(description = "Confidence score for assistant answers; null for history messages without scoring", example = "0.87")
    private Double confidenceScore;

    @Schema(description = "Confidence level derived from confidence score", example = "HIGH")
    private String confidenceLevel;

    @Schema(description = "Estimated token usage for the generated answer", example = "624")
    private Integer tokensUsed;

    @Schema(description = "Model response latency in milliseconds", example = "1420")
    private Long responseTimeMs;

    @ArraySchema(schema = @Schema(description = "Distinct document titles used as grounding sources", example = "Giao trinh Vat Ly 10 - Chuong 2"))
    private List<String> sources;

    @Schema(description = "Message creation timestamp", example = "2026-04-12T14:32:10")
    private LocalDateTime createdAt;
}

