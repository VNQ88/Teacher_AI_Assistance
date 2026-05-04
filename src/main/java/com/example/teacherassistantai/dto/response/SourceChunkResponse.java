package com.example.teacherassistantai.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Detailed RAG source chunk used to ground an assistant answer")
public class SourceChunkResponse {

    @Schema(description = "1-based source index used in answer citations", example = "1")
    private Integer sourceIndex;

    @Schema(description = "Document chunk id", example = "984")
    private Long chunkId;

    @Schema(description = "Document id", example = "42")
    private Long documentId;

    @Schema(description = "Document title", example = "Giao trinh Triet hoc Mac Lenin")
    private String documentTitle;

    @Schema(description = "Hierarchical section path", example = "Chuong 1 > I. Khai niem")
    private String sectionPath;

    @Schema(description = "Source start page when available", example = "12")
    private Integer pageFrom;

    @Schema(description = "Source end page when available", example = "13")
    private Integer pageTo;

    @Schema(description = "Chunk type", example = "TEXT")
    private String chunkType;

    @Schema(description = "Fallback character start offset in normalized markdown", example = "1520")
    private Integer charStart;

    @Schema(description = "Fallback character end offset in normalized markdown", example = "2210")
    private Integer charEnd;

    @Schema(description = "Short text excerpt from the chunk")
    private String snippet;
}
