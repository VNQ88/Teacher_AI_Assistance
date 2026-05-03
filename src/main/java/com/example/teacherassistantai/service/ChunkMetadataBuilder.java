package com.example.teacherassistantai.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ChunkMetadataBuilder {

    public Map<String, Object> buildJsonb(Integer pageFrom,
                                          Integer pageTo,
                                          String sectionHeader,
                                          String chunkType,
                                          Integer charCount) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("pageFrom", pageFrom);
        metadata.put("pageTo", pageTo);
        metadata.put("sectionHeader", sectionHeader);
        metadata.put("chunkType", chunkType);
        metadata.put("charCount", charCount);
        return metadata;
    }

    public Map<String, Object> buildHierarchicalJsonb(Integer pageFrom,
                                                      Integer pageTo,
                                                      String sectionHeader,
                                                      String chunkType,
                                                      Integer charCount,
                                                      String nodeType,
                                                      String nodeId,
                                                      String parentNodeId,
                                                      List<String> breadcrumb,
                                                      Integer charStart,
                                                      Integer charEnd) {
        Map<String, Object> metadata = buildJsonb(pageFrom, pageTo, sectionHeader, chunkType, charCount);
        metadata.put("nodeType", nodeType);
        metadata.put("nodeId", nodeId);
        metadata.put("parentNodeId", parentNodeId);
        metadata.put("breadcrumb", breadcrumb);
        metadata.put("charStart", charStart);
        metadata.put("charEnd", charEnd);
        metadata.put("hierarchical", true);
        return metadata;
    }
}
