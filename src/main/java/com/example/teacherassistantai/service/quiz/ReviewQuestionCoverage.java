package com.example.teacherassistantai.service.quiz;

public record ReviewQuestionCoverage(
        int expectedChildCount,
        int usedChildSummaryCount,
        int fallbackChildCount,
        int representativeChildCount,
        int rawChunkCount,
        int allowedCitationChunkCount,
        boolean complete
) {
    public static ReviewQuestionCoverage rawChunks(int rawChunkCount, int allowedCitationChunkCount) {
        return new ReviewQuestionCoverage(0, 0, 0, 0, rawChunkCount, allowedCitationChunkCount, rawChunkCount > 0);
    }
}
