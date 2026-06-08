package com.example.teacherassistantai.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class DocumentArtifactEmbeddingBackfillRequest {

    @Min(value = 1, message = "Số lượng mỗi lô phải lớn hơn hoặc bằng 1")
    @Max(value = 200, message = "Số lượng mỗi lô không được vượt quá 200")
    private Integer batchSize = 25;

    @Min(value = 1, message = "Số lô tối đa phải lớn hơn hoặc bằng 1")
    @Max(value = 100, message = "Số lô tối đa không được vượt quá 100")
    private Integer maxBatches = 1;
}
