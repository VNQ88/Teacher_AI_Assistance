package com.example.teacherassistantai.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RagSourceResponse {
    private Long chunkId;
    private Long documentId;
    private String documentTitle;
    private Double score;
    private String excerpt;
}

