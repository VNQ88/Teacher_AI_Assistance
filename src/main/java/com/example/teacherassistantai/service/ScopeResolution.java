package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.DocumentNode;

import java.util.List;
import java.util.Optional;

public record ScopeResolution(
        Status status,
        DocumentNode node,
        double confidence,
        String reason,
        List<DocumentNode> candidates
) {
    public enum Status {
        RESOLVED,
        AMBIGUOUS,
        NOT_FOUND
    }

    public static ScopeResolution resolved(DocumentNode node, double confidence, String reason, List<DocumentNode> candidates) {
        return new ScopeResolution(Status.RESOLVED, node, confidence, reason, safeCandidates(candidates));
    }

    public static ScopeResolution ambiguous(String reason, List<DocumentNode> candidates) {
        return new ScopeResolution(Status.AMBIGUOUS, null, 0.0, reason, safeCandidates(candidates));
    }

    public static ScopeResolution notFound(String reason) {
        return new ScopeResolution(Status.NOT_FOUND, null, 0.0, reason, List.of());
    }

    public Optional<DocumentNode> resolvedNode() {
        return status == Status.RESOLVED ? Optional.ofNullable(node) : Optional.empty();
    }

    private static List<DocumentNode> safeCandidates(List<DocumentNode> candidates) {
        return candidates == null ? List.of() : List.copyOf(candidates);
    }
}
