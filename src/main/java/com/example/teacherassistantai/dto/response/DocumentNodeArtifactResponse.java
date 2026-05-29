package com.example.teacherassistantai.dto.response;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class DocumentNodeArtifactResponse {

    private Long id;
    private Long documentId;
    private Long documentNodeId;
    private String nodeType;
    private String nodeTitle;
    private String sectionPath;
    private DocumentNodeArtifactType artifactType;
    private DocumentNodeArtifactStatus status;
    private String promptVersion;
    private String model;
    private String sourceHash;
    private Integer tokenCount;
    private String errorMessage;
    private Map<String, Object> contentJsonb;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
