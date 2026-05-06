package com.example.teacherassistantai.service;

import java.util.Map;

public record DocumentNodeArtifactGenerationResult(
        Map<String, Object> contentJsonb,
        Integer tokenCount
) {
}
