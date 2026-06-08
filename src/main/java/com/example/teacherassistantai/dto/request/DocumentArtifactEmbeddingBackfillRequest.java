package com.example.teacherassistantai.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class DocumentArtifactEmbeddingBackfillRequest {

    @Min(1)
    @Max(200)
    private Integer batchSize = 25;

    @Min(1)
    @Max(100)
    private Integer maxBatches = 1;
}
