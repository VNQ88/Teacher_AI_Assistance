package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.DocumentChunk;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class RagConfidenceService {

    public double score(String question, List<DocumentChunk> sources, String answer) {
        if (sources == null || sources.isEmpty()) {
            return 0.20;
        }

        double sourceSupport = Math.min(1.0, sources.size() / 5.0);
        double citationSupport = computeCitationSupport(answer, sources);
        double lexicalEvidence = computeLexicalEvidence(question, answer, sources);
        double answerPresence = (answer == null || answer.isBlank()) ? 0.0 : 1.0;

        double score = lexicalEvidence * 0.45
                + citationSupport * 0.25
                + sourceSupport * 0.20
                + answerPresence * 0.10;
        return Math.max(0.05, Math.min(0.95, score));
    }

    public String level(double score) {
        if (score >= 0.80) return "HIGH";
        if (score >= 0.55) return "MEDIUM";
        return "LOW";
    }

    private double computeCitationSupport(String answer, List<DocumentChunk> sources) {
        if (answer == null || answer.isBlank()) {
            return 0.0;
        }
        int matched = 0;
        for (DocumentChunk source : sources) {
            if (source.getId() != null && answer.contains("[Chunk " + source.getId() + "]")) {
                matched++;
            }
        }
        return Math.min(1.0, matched / 2.0);
    }

    private double computeLexicalEvidence(String question, String answer, List<DocumentChunk> sources) {
        if (answer == null || answer.isBlank()) {
            return 0.0;
        }

        Set<String> questionTokens = tokens(question);
        Set<String> answerTokens = tokens(answer);
        if (questionTokens.isEmpty() || answerTokens.isEmpty()) {
            return 0.0;
        }

        int questionOverlap = 0;
        for (String token : questionTokens) {
            if (answerTokens.contains(token)) {
                questionOverlap++;
            }
        }
        double questionCoverage = (double) questionOverlap / questionTokens.size();

        int maxSourceOverlap = 0;
        for (DocumentChunk source : sources) {
            Set<String> sourceTokens = tokens(source.getContent());
            int overlap = 0;
            for (String token : answerTokens) {
                if (sourceTokens.contains(token)) {
                    overlap++;
                }
            }
            maxSourceOverlap = Math.max(maxSourceOverlap, overlap);
        }
        double sourceCoverage = Math.min(1.0, maxSourceOverlap / 18.0);
        return Math.min(1.0, questionCoverage * 0.5 + sourceCoverage * 0.5);
    }

    private Set<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String[] raw = text.toLowerCase().replaceAll("[^\\p{L}\\p{N}\\s]", " ").trim().split("\\s+");
        Set<String> tokens = new HashSet<>();
        for (String token : raw) {
            if (token.length() >= 2 || token.chars().allMatch(Character::isDigit)) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}

