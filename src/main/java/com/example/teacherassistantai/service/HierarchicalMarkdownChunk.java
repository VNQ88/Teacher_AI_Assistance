package com.example.teacherassistantai.service;

import java.util.List;

public record HierarchicalMarkdownChunk(
        String content,
        String chunkType,
        String nodeType,
        String nodeId,
        String parentNodeId,
        String sectionHeader,
        List<String> breadcrumb,
        Integer pageFrom,
        Integer pageTo,
        Integer charStart,
        Integer charEnd
) {
}
