package com.example.teacherassistantai.dto.response;

import com.example.teacherassistantai.common.enumerate.DocumentEnrichmentStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class DocumentEnrichmentJobResponse {

    private Long documentId;
    private Long documentNodeId;
    private DocumentStatus documentStatus;
    private DocumentEnrichmentStatus enrichmentStatus;
    private List<DocumentNodeArtifactType> artifactTypes;
    private Boolean forceRegenerate;
    private String message;
    private LocalDateTime queuedAt;
}
