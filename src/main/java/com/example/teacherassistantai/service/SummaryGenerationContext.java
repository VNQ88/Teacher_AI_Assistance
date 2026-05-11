package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;

import java.util.List;
import java.util.Map;

public record SummaryGenerationContext(
        Document document,
        DocumentNode node,
        SummaryMode summaryMode,
        List<DocumentChunk> directChunks,
        List<ChildSummary> childSummaries,
        SummaryCoverage coverage,
        Map<Long, List<DocumentChunk>> fallbackRawChunks
) {
    public SummaryGenerationContext(Document document,
                                    DocumentNode node,
                                    SummaryMode summaryMode,
                                    List<DocumentChunk> directChunks,
                                    List<ChildSummary> childSummaries,
                                    SummaryCoverage coverage) {
        this(document, node, summaryMode, directChunks, childSummaries, coverage, Map.of());
    }
}
