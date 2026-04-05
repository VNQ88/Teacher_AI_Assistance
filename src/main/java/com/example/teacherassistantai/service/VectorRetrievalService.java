package com.example.teacherassistantai.service;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.integration.gemini.GeminiEmbeddingGateway;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class VectorRetrievalService {

    private final GeminiEmbeddingGateway embeddingGateway;
    private final DocumentChunkRepository documentChunkRepository;
    private final RagProperties ragProperties;

    public List<DocumentChunk> retrieve(ChatSession session, String question, int topK) {
        int safeTopK = Math.min(Math.max(1, topK), ragProperties.getMaxTopK());
        int candidateTopK = Math.max(safeTopK, ragProperties.getCandidateTopK());

        List<Double> questionEmbedding = embeddingGateway.embed(question);
        validateEmbeddingDimensions(questionEmbedding);

        String literal = toVectorLiteral(questionEmbedding);
        Long classroomId = session.getClassroom() != null ? session.getClassroom().getId() : null;
        List<DocumentChunk> candidates = documentChunkRepository.searchBySubjectVector(
                session.getSubject().getId(),
                classroomId,
                literal,
                ragProperties.getMinChunkChars(),
                candidateTopK
        );

        return candidates.stream()
                .sorted(Comparator.comparingDouble((DocumentChunk chunk) -> rerankScore(question, chunk)).reversed())
                .limit(safeTopK)
                .toList();
    }

    private void validateEmbeddingDimensions(List<Double> embedding) {
        int actual = embedding == null ? 0 : embedding.size();
        int expected = ragProperties.getEmbeddingDimensions();
        if (actual != expected) {
            throw new InvalidDataException("Question embedding dimension mismatch: expected %d, got %d"
                    .formatted(expected, actual));
        }
    }

    private double rerankScore(String question, DocumentChunk chunk) {
        Set<String> questionTokens = normalizeTokens(question);
        Set<String> chunkTokens = normalizeTokens(chunk.getContent());
        if (questionTokens.isEmpty() || chunkTokens.isEmpty()) {
            return 0.0;
        }

        int overlap = 0;
        for (String token : questionTokens) {
            if (chunkTokens.contains(token)) {
                overlap++;
            }
        }

        double overlapRatio = (double) overlap / questionTokens.size();
        double lengthPenalty = Math.min(1.0, Math.max(0.2, chunk.getContent().length() / 1200.0));
        return overlapRatio * 0.85 + lengthPenalty * 0.15;
    }

    private Set<String> normalizeTokens(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }

        String normalized = text.toLowerCase().replaceAll("[^\\p{L}\\p{N}\\s]", " ");
        String[] rawTokens = normalized.trim().split("\\s+");
        Set<String> tokens = new HashSet<>();
        for (String token : rawTokens) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String toVectorLiteral(List<Double> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) builder.append(',');
            builder.append(values.get(i));
        }
        builder.append(']');
        return builder.toString();
    }
}
