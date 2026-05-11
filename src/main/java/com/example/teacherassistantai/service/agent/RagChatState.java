package com.example.teacherassistantai.service.agent;

import com.example.teacherassistantai.common.enumerate.AgentType;
import com.example.teacherassistantai.entity.ChatMessage;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.service.RagChatIntent;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Builder(toBuilder = true)
@Getter
public class RagChatState {
    // Input
    private final String question;
    private final ChatSession session;
    private final Integer requestedTopK;
    private final Double requestedTemperature;
    // Intent
    private final RagChatIntent intent;
    // RAG path
    private final List<ChatMessage> history;
    private final List<DocumentChunk> retrievedChunks;
    private final int topK;
    private final int retryCount;
    // LLM output
    private final String answer;
    private final double confidenceScore;
    private final String confidenceLevel;
    // Artifact path
    private final DocumentNode resolvedNode;
    private final boolean artifactHit;
    private final boolean ragFallback;
    // Final
    private final AgentType agentType;
    private final List<DocumentChunk> sources;
    private final long startedAt;
}
