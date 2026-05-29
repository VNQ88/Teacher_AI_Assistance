package com.example.teacherassistantai.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class DocumentHierarchyDebugResponse {
    private Long documentId;
    private String title;
    private Integer nodeCount;
    private List<DocumentNodeDebugResponse> roots;
}
