package com.example.teacherassistantai.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DocumentChunkDebugResponse {
    private Long id;
    private Integer chunkIndex;
    private Integer sourceOrder;
    private String chunkType;
    private Long nodeId;
    private String nodeKey;
    private Long parentNodeId;
    private String parentNodeKey;
    private String sectionPath;
    private Integer pageFrom;
    private Integer pageTo;
    private Integer tokenCount;
    private Integer charStart;
    private Integer charEnd;
    private String snippet;
}
