package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;

import java.util.List;

public record SummaryGenerationContext(
        Document document,
        DocumentNode node,
        SummaryMode summaryMode,
        List<DocumentChunk> directChunks,
        List<ChildSummary> childSummaries,
        SummaryCoverage coverage
) {
}
