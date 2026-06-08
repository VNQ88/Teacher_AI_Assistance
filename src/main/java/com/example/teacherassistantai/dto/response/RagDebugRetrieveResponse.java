package com.example.teacherassistantai.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class RagDebugRetrieveResponse {
    private String query;
    private String retrievalMode;
    private Long scopedNodeId;
    private String intentType;
    private Integer sectionNumber;
    private Integer candidateCount;
    private Integer policyCandidateCount;
    private Integer selectedCount;
    private Map<String, Long> selectedChunkTypes;
    private Integer coarseHitCount;
    private List<CoarseHit> coarseHits;
    private Integer fineCandidateCount;
    private Integer flatGuardrailCandidateCount;
    private String fallbackReason;
    private List<ParentGroup> parentGroups;
    private List<Chunk> candidateChunks;
    private List<Chunk> selectedChunks;
    private String promptContextPreview;

    @Data
    @Builder
    public static class CoarseHit {
        private Long artifactId;
        private Long nodeId;
        private Long documentId;
        private String documentTitle;
        private String nodeType;
        private String sectionPath;
        private Double distance;
    }

    @Data
    @Builder
    public static class ParentGroup {
        private String parentKey;
        private Double score;
        private Integer childCount;
        private List<Chunk> children;
    }

    @Data
    @Builder
    public static class Chunk {
        private Long chunkId;
        private Integer chunkIndex;
        private Integer sourceOrder;
        private String chunkType;
        private Long documentId;
        private String documentTitle;
        private Long nodeId;
        private String nodeKey;
        private Long parentNodeId;
        private String parentNodeKey;
        private String sectionPath;
        private Integer pageFrom;
        private Integer pageTo;
        private Double score;
        private String snippet;
    }
}
