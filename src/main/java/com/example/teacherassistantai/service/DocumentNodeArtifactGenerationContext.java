package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.integration.ai.AiWorkload;
import com.example.teacherassistantai.service.quiz.ReviewQuestionGenerationContext;

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
        int maxContextChars,
        ReviewQuestionGenerationContext reviewQuestionContext,
        AiWorkload workloadOverride
) {
    public DocumentNodeArtifactGenerationContext(Document document,
                                                 DocumentNode node,
                                                 DocumentNodeArtifactType artifactType,
                                                 List<DocumentChunk> chunks,
                                                 String sourceHash,
                                                 String promptVersion,
                                                 String model,
                                                 int minQuestionCount,
                                                 int maxQuestionCount,
                                                 int maxContextChars) {
        this(document, node, artifactType, chunks, sourceHash, promptVersion, model,
                minQuestionCount, maxQuestionCount, maxContextChars, null, null);
    }

    public DocumentNodeArtifactGenerationContext(Document document,
                                                 DocumentNode node,
                                                 DocumentNodeArtifactType artifactType,
                                                 List<DocumentChunk> chunks,
                                                 String sourceHash,
                                                 String promptVersion,
                                                 String model,
                                                 int minQuestionCount,
                                                 int maxQuestionCount,
                                                 int maxContextChars,
                                                 ReviewQuestionGenerationContext reviewQuestionContext) {
        this(document, node, artifactType, chunks, sourceHash, promptVersion, model,
                minQuestionCount, maxQuestionCount, maxContextChars, reviewQuestionContext, null);
    }
}
