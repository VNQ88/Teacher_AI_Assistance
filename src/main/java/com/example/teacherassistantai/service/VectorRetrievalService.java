package com.example.teacherassistantai.service;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.integration.gemini.GeminiEmbeddingGateway;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class VectorRetrievalService {

    private static final Pattern SECTION_INTENT_PATTERN = Pattern.compile(
            "(?i)(?:\\b(section|chapter|part)\\s*(\\d{1,3})\\b|\\b(phan|chuong|muc)\\s*(\\d{1,3})\\b)");

    private final GeminiEmbeddingGateway embeddingGateway;
    private final DocumentChunkRepository documentChunkRepository;
    private final RagProperties ragProperties;

    public List<DocumentChunk> retrieve(ChatSession session, String question, int topK) {
        int safeTopK = Math.min(Math.max(1, topK), ragProperties.getMaxTopK());
        int candidateTopK = Math.max(safeTopK, ragProperties.getCandidateTopK());
        RetrievalIntent intent = detectIntent(question);

        List<Double> questionEmbedding = embeddingGateway.embed(question);
        validateEmbeddingDimensions(questionEmbedding);

        String literal = toVectorLiteral(questionEmbedding);
        List<DocumentChunk> candidates = documentChunkRepository.searchBySubjectVector(
                session.getSubject().getId(),
                literal,
                ragProperties.getMinChunkChars(),
                candidateTopK,
                intent.sectionNumber()
        );

        List<ScoredChunk> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            DocumentChunk chunk = candidates.get(i);
            scored.add(new ScoredChunk(chunk, rerankScore(question, chunk, intent, i, candidates.size())));
        }

        List<DocumentChunk> selected = scored.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .map(ScoredChunk::chunk)
                .limit(safeTopK)
                .toList();

        logRetrievalDiagnostics(question, intent, selected);
        return selected;
    }

    private void validateEmbeddingDimensions(List<Double> embedding) {
        int actual = embedding == null ? 0 : embedding.size();
        int expected = ragProperties.getEmbeddingDimensions();
        if (actual != expected) {
            throw new InvalidDataException("Question embedding dimension mismatch: expected %d, got %d"
                    .formatted(expected, actual));
        }
    }

    private double rerankScore(String question,
                               DocumentChunk chunk,
                               RetrievalIntent intent,
                               int candidateRank,
                               int candidateCount) {
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
        double lengthPrior = Math.min(1.0, Math.max(0.2, chunk.getContent().length() / 1200.0));

        double vectorRankPrior = candidateCount <= 1
                ? 1.0
                : 1.0 - ((double) candidateRank / (candidateCount - 1));

        double sectionBoost = 0.0;
        if (intent.sectionNumber() != null && containsSectionNumber(chunk.getContent(), intent.sectionNumber())) {
            sectionBoost = 1.0;
        }

        return overlapRatio * 0.45
                + vectorRankPrior * 0.25
                + sectionBoost * 0.20
                + lengthPrior * 0.10;
    }

    private Set<String> normalizeTokens(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }

        String normalized = text.toLowerCase().replaceAll("[^\\p{L}\\p{N}\\s]", " ");
        String[] rawTokens = normalized.trim().split("\\s+");
        Set<String> tokens = new HashSet<>();
        for (String token : rawTokens) {
            if (token.length() >= 2 || token.chars().allMatch(Character::isDigit)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private RetrievalIntent detectIntent(String question) {
        if (question == null || question.isBlank()) {
            return new RetrievalIntent(null);
        }
        Matcher matcher = SECTION_INTENT_PATTERN.matcher(question);
        if (!matcher.find()) {
            return new RetrievalIntent(null);
        }

        String number = matcher.group(2) != null ? matcher.group(2) : matcher.group(4);
        try {
            return new RetrievalIntent(Integer.parseInt(number));
        } catch (NumberFormatException ex) {
            return new RetrievalIntent(null);
        }
    }

    private boolean containsSectionNumber(String content, Integer sectionNumber) {
        if (content == null || sectionNumber == null) {
            return false;
        }
        String lowered = content.toLowerCase();
        String n = sectionNumber.toString();
        return lowered.contains("phan " + n)
                || lowered.contains("chuong " + n)
                || lowered.contains("muc " + n)
                || lowered.contains("section " + n)
                || lowered.contains("chapter " + n)
                || lowered.contains("part " + n);
    }

    private void logRetrievalDiagnostics(String question, RetrievalIntent intent, List<DocumentChunk> selected) {
        if (!log.isDebugEnabled()) {
            return;
        }
        long sectionMatches = selected.stream()
                .filter(c -> containsSectionNumber(c.getContent(), intent.sectionNumber()))
                .count();
        log.debug("RAG retrieval diagnostics: question='{}', sectionIntent={}, selected={}, sectionMatches={}",
                question,
                intent.sectionNumber(),
                selected.size(),
                sectionMatches);
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

    private record RetrievalIntent(Integer sectionNumber) {
    }

    private record ScoredChunk(DocumentChunk chunk, double score) {
    }
}
