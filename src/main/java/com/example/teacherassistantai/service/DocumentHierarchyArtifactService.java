package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.Document;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DocumentHierarchyArtifactService {

    private final MarkdownChunkingService markdownChunkingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DocumentHierarchyArtifactService(MarkdownChunkingService markdownChunkingService) {
        this.markdownChunkingService = markdownChunkingService;
    }

    public Artifacts buildArtifacts(Document document, String markdown) {
        MarkdownChunkingService.HierarchicalMarkdownDocument hierarchyDocument =
                markdownChunkingService.parseHierarchicalDocument(markdown);
        List<HierarchicalMarkdownChunk> chunks = hierarchyDocument.chunks();
        return new Artifacts(
                hierarchyDocument,
                hierarchyDocument.normalizedMarkdown(),
                chunks,
                toHierarchyJson(document, hierarchyDocument, chunks),
                toChunksJsonl(document, chunks)
        );
    }

    private String toHierarchyJson(Document document,
                                   MarkdownChunkingService.HierarchicalMarkdownDocument hierarchyDocument,
                                   List<HierarchicalMarkdownChunk> chunks) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", document.getId());
        payload.put("title", document.getTitle());
        payload.put("sourceObjectKey", document.getOriginalObjectKey());
        payload.put("markdownObjectKey", document.getMarkdownObjectKey());
        payload.put("nodeCount", countNodes(hierarchyDocument.root()));
        payload.put("chunkCount", chunks.size());
        payload.put("root", hierarchyDocument.root());
        payload.put("nodes", flattenNodes(hierarchyDocument.root()));
        return writeJson(payload);
    }

    private int countNodes(MarkdownChunkingService.PublicHierarchyNode node) {
        int total = 1;
        for (MarkdownChunkingService.PublicHierarchyNode child : node.children()) {
            total += countNodes(child);
        }
        return total;
    }

    private List<MarkdownChunkingService.PublicHierarchyNode> flattenNodes(MarkdownChunkingService.PublicHierarchyNode root) {
        java.util.ArrayList<MarkdownChunkingService.PublicHierarchyNode> nodes = new java.util.ArrayList<>();
        collectNodes(root, nodes);
        return nodes;
    }

    private void collectNodes(MarkdownChunkingService.PublicHierarchyNode node,
                              List<MarkdownChunkingService.PublicHierarchyNode> nodes) {
        nodes.add(node);
        for (MarkdownChunkingService.PublicHierarchyNode child : node.children()) {
            collectNodes(child, nodes);
        }
    }

    private String toChunksJsonl(Document document, List<HierarchicalMarkdownChunk> chunks) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            HierarchicalMarkdownChunk chunk = chunks.get(i);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("documentId", document.getId());
            payload.put("chunkIndex", i + 1);
            payload.put("chunkType", chunk.chunkType());
            payload.put("nodeType", chunk.nodeType());
            payload.put("nodeId", chunk.nodeId());
            payload.put("parentNodeId", chunk.parentNodeId());
            payload.put("sectionHeader", chunk.sectionHeader());
            payload.put("breadcrumb", chunk.breadcrumb());
            payload.put("pageFrom", chunk.pageFrom());
            payload.put("pageTo", chunk.pageTo());
            payload.put("charStart", chunk.charStart());
            payload.put("charEnd", chunk.charEnd());
            payload.put("content", chunk.content());
            builder.append(writeJson(payload)).append('\n');
        }
        return builder.toString();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize document hierarchy artifact", ex);
        }
    }

    public record Artifacts(
            MarkdownChunkingService.HierarchicalMarkdownDocument hierarchyDocument,
            String normalizedMarkdown,
            List<HierarchicalMarkdownChunk> chunks,
            String hierarchyJson,
            String chunksJsonl
    ) {
    }

}
