package com.example.teacherassistantai.service.quiz;

import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.service.ChildSummary;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ReviewQuestionGenerationContext(
        Document document,
        DocumentNode node,
        QuizInputMode inputMode,
        List<DocumentChunk> rawChunks,
        List<ChildSummary> childSummaries,
        Map<Long, List<DocumentChunk>> fallbackRawChunks,
        Map<Long, List<DocumentChunk>> representativeChildChunks,
        List<DocumentChunk> allowedCitationChunks,
        int minQuestionCount,
        int maxQuestionCount,
        int summaryBasedTargetCount,
        int representativeTargetCount,
        ReviewQuestionCoverage coverage,
        String sourceHash
) {
    public ReviewQuestionGenerationContext {
        rawChunks = rawChunks == null ? List.of() : List.copyOf(rawChunks);
        childSummaries = childSummaries == null ? List.of() : List.copyOf(childSummaries);
        fallbackRawChunks = copyChunkMap(fallbackRawChunks);
        representativeChildChunks = copyChunkMap(representativeChildChunks);
        allowedCitationChunks = allowedCitationChunks == null ? List.of() : List.copyOf(allowedCitationChunks);
    }

    public boolean hasUsableContext() {
        return !allowedCitationChunks.isEmpty()
                || !rawChunks.isEmpty()
                || !childSummaries.isEmpty()
                || !fallbackRawChunks.isEmpty()
                || !representativeChildChunks.isEmpty();
    }

    private static Map<Long, List<DocumentChunk>> copyChunkMap(Map<Long, List<DocumentChunk>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<DocumentChunk>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, value == null ? List.of() : List.copyOf(value)));
        return Collections.unmodifiableMap(copy);
    }
}
