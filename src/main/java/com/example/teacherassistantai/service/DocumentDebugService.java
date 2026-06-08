package com.example.teacherassistantai.service;

import com.example.teacherassistantai.dto.response.DocumentChunkDebugResponse;
import com.example.teacherassistantai.dto.response.DocumentHierarchyDebugResponse;
import com.example.teacherassistantai.dto.response.DocumentNodeDebugResponse;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DocumentDebugService {

    private final DocumentRepository documentRepository;
    private final DocumentNodeRepository documentNodeRepository;
    private final DocumentChunkRepository documentChunkRepository;

    @Transactional(readOnly = true)
    public DocumentHierarchyDebugResponse getHierarchy(Long documentId) {
        Document document = getDocument(documentId);
        List<DocumentNode> nodes = documentNodeRepository.findByDocumentIdOrderByOrderIndexAsc(documentId);
        Map<Long, DocumentNodeDebugResponse> responseById = new LinkedHashMap<>();
        Map<Long, List<DocumentNodeDebugResponse>> childrenByParentId = new LinkedHashMap<>();
        List<DocumentNodeDebugResponse> roots = new ArrayList<>();

        for (DocumentNode node : nodes) {
            DocumentNodeDebugResponse response = toNodeResponse(node, new ArrayList<>());
            responseById.put(node.getId(), response);
            Long parentId = node.getParent() == null ? null : node.getParent().getId();
            if (parentId == null) {
                roots.add(response);
            } else {
                childrenByParentId.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(response);
            }
        }

        for (Map.Entry<Long, List<DocumentNodeDebugResponse>> entry : childrenByParentId.entrySet()) {
            DocumentNodeDebugResponse parent = responseById.get(entry.getKey());
            if (parent != null) {
                parent.setChildren(entry.getValue());
            }
        }

        return DocumentHierarchyDebugResponse.builder()
                .documentId(document.getId())
                .title(document.getTitle())
                .nodeCount(nodes.size())
                .roots(roots)
                .build();
    }

    @Transactional(readOnly = true)
    public List<DocumentNodeDebugResponse> getNodes(Long documentId) {
        getDocument(documentId);
        return documentNodeRepository.findByDocumentIdOrderByOrderIndexAsc(documentId)
                .stream()
                .map(node -> toNodeResponse(node, List.of()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DocumentChunkDebugResponse> getChunks(Long documentId, String type) {
        getDocument(documentId);
        List<DocumentChunk> chunks = StringUtils.hasText(type)
                ? documentChunkRepository.findByDocumentIdAndChunkTypeOrderBySourceOrderAsc(documentId, type.toUpperCase(Locale.ROOT))
                : documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId);
        return chunks.stream()
                .map(this::toChunkResponse)
                .toList();
    }

    private Document getDocument(Long documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài liệu với id: " + documentId));
    }

    private DocumentNodeDebugResponse toNodeResponse(DocumentNode node, List<DocumentNodeDebugResponse> children) {
        return DocumentNodeDebugResponse.builder()
                .id(node.getId())
                .nodeKey(node.getNodeKey())
                .parentId(node.getParent() == null ? null : node.getParent().getId())
                .parentNodeKey(node.getParent() == null ? null : node.getParent().getNodeKey())
                .nodeType(node.getNodeType())
                .level(node.getLevel())
                .title(node.getTitle())
                .sectionPath(node.getSectionPath())
                .orderIndex(node.getOrderIndex())
                .pageFrom(node.getPageFrom())
                .pageTo(node.getPageTo())
                .contentCharCount(metadataInt(node.getMetadataJsonb(), "contentCharCount"))
                .charStart(metadataInt(node.getMetadataJsonb(), "charStart"))
                .charEnd(metadataInt(node.getMetadataJsonb(), "charEnd"))
                .children(children)
                .build();
    }

    private DocumentChunkDebugResponse toChunkResponse(DocumentChunk chunk) {
        DocumentNode node = chunk.getNode();
        DocumentNode parentNode = chunk.getParentNode();
        return DocumentChunkDebugResponse.builder()
                .id(chunk.getId())
                .chunkIndex(chunk.getChunkIndex())
                .sourceOrder(chunk.getSourceOrder())
                .chunkType(chunk.getChunkType())
                .nodeId(node == null ? null : node.getId())
                .nodeKey(node == null ? null : node.getNodeKey())
                .parentNodeId(parentNode == null ? null : parentNode.getId())
                .parentNodeKey(parentNode == null ? null : parentNode.getNodeKey())
                .sectionPath(chunk.getSectionPath())
                .pageFrom(chunk.getPageFrom())
                .pageTo(chunk.getPageTo())
                .tokenCount(chunk.getTokenCount())
                .charStart(metadataInt(chunk.getMetadataJsonb(), "charStart"))
                .charEnd(metadataInt(chunk.getMetadataJsonb(), "charEnd"))
                .snippet(snippet(chunk.getContent()))
                .build();
    }

    private Integer metadataInt(Map<String, Object> metadata, String key) {
        if (metadata == null || !metadata.containsKey(key)) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String stringValue && stringValue.matches("-?\\d+")) {
            return Integer.parseInt(stringValue);
        }
        return null;
    }

    private String snippet(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 320) {
            return normalized;
        }
        return normalized.substring(0, 317) + "...";
    }
}
