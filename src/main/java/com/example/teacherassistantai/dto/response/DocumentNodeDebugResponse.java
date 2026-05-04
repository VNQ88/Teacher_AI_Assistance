package com.example.teacherassistantai.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class DocumentNodeDebugResponse {
    private Long id;
    private String nodeKey;
    private Long parentId;
    private String parentNodeKey;
    private String nodeType;
    private Integer level;
    private String title;
    private String sectionPath;
    private Integer orderIndex;
    private Integer pageFrom;
    private Integer pageTo;
    private Integer contentCharCount;
    private Integer charStart;
    private Integer charEnd;
    private List<DocumentNodeDebugResponse> children;
}
