package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.DocumentChunk;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagConfidenceService {

    public double score(List<DocumentChunk> sources, String answer) {
        if (sources == null || sources.isEmpty()) return 0.3;
        double base = Math.min(0.9, 0.45 + (sources.size() * 0.08));
        if (answer == null || answer.isBlank()) return Math.min(base, 0.4);
        return Math.min(0.95, base);
    }

    public String level(double score) {
        if (score >= 0.75) return "HIGH";
        if (score >= 0.45) return "MEDIUM";
        return "LOW";
    }
}

