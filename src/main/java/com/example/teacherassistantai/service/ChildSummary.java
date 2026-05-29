package com.example.teacherassistantai.service;

import java.util.List;
import java.util.Map;

public record ChildSummary(
        Long nodeId,
        String nodeType,
        String title,
        String sectionPath,
        Long artifactId,
        String sourceHash,
        String summary,
        List<Map<String, Object>> citations
) {
}
