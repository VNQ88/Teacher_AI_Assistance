package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;

public interface DocumentNodeArtifactGenerator {

    boolean supports(DocumentNodeArtifactType artifactType);

    DocumentNodeArtifactGenerationResult generate(DocumentNodeArtifactGenerationContext context);

    default boolean supportsSummaryMode(SummaryMode summaryMode) {
        return false;
    }

    default DocumentNodeArtifactGenerationResult generateSummary(SummaryGenerationContext context) {
        throw new UnsupportedOperationException("Summary generation mode is not supported: " + context.summaryMode());
    }
}
