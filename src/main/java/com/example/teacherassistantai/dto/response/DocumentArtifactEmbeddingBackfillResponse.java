package com.example.teacherassistantai.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class DocumentArtifactEmbeddingBackfillResponse {

    private Long documentId;
    private Long subjectId;
    private Integer batchSize;
    private Integer maxBatches;
    private Boolean queued;
    private String message;
    private LocalDateTime queuedAt;
    private Long totalCompletedSummaries;
    private Long embeddedCurrent;
    private Long pending;
    private String embeddingModel;
    private Integer embeddingDimensions;
}
