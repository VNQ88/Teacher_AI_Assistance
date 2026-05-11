package com.example.teacherassistantai.service.agent;

import com.example.teacherassistantai.entity.DocumentChunk;

import java.util.List;

public record AgentResult(
        String answer,
        List<DocumentChunk> sources,
        double confidenceScore,
        String confidenceLevel,
        boolean artifactHit,
        boolean ragFallback
) {
    public static AgentResult hit(String answer, List<DocumentChunk> sources) {
        return new AgentResult(answer, sources, 1.0, "HIGH", true, false);
    }

    public static AgentResult fallback(String answer, List<DocumentChunk> sources) {
        return new AgentResult(answer, sources, 0.35, "LOW", false, true);
    }

    public static AgentResult message(String text) {
        return new AgentResult(text, List.of(), 0.0, "LOW", false, false);
    }
}
