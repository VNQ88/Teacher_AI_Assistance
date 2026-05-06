package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;

import java.util.List;

public record DocumentNodeArtifactGenerationContext(
        Document document,
        DocumentNode node,
        DocumentNodeArtifactType artifactType,
        List<DocumentChunk> chunks,
        String sourceHash,
        String promptVersion,
        String model,
        int minQuestionCount,
        int maxQuestionCount,
        int maxContextChars
) {
}
