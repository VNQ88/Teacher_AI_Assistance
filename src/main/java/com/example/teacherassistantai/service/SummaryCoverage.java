package com.example.teacherassistantai.service;

import java.util.List;

public record SummaryCoverage(
        int expectedChildCount,
        int usedChildCount,
        List<Long> missingChildNodeIds,
        int directChunkCount,
        int usedDirectChunkCount,
        boolean complete,
        int fallbackChildCount
) {
    public SummaryCoverage(int expectedChildCount,
                           int usedChildCount,
                           List<Long> missingChildNodeIds,
                           int directChunkCount,
                           int usedDirectChunkCount,
                           boolean complete) {
        this(expectedChildCount, usedChildCount, missingChildNodeIds,
                directChunkCount, usedDirectChunkCount, complete, 0);
    }

    public static SummaryCoverage chunksOnly(int directChunkCount, int usedDirectChunkCount) {
        return new SummaryCoverage(0, 0, List.of(), directChunkCount, usedDirectChunkCount, true, 0);
    }
}
