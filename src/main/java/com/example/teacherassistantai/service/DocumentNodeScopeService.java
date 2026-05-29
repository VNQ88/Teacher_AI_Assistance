package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DocumentNodeScopeService {

    private final DocumentNodeRepository documentNodeRepository;
    private final DocumentChunkRepository documentChunkRepository;

    @Transactional(readOnly = true)
    public NodeScope getScope(Long rootNodeId) {
        DocumentNode rootNode = documentNodeRepository.findById(rootNodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Document node not found with id: " + rootNodeId));

        List<DocumentChunk> chunks = documentChunkRepository.findScopeChunksByRootNodeId(rootNodeId)
                .stream()
                .sorted(Comparator
                        .comparingInt(this::sourceOrder)
                        .thenComparing(chunk -> chunk.getId() == null ? Long.MAX_VALUE : chunk.getId()))
                .toList();

        return new NodeScope(rootNode, chunks, sourceHash(rootNode, chunks));
    }

    public String sourceHash(DocumentNode rootNode, List<DocumentChunk> chunks) {
        MessageDigest digest = sha256();
        update(digest, "documentNodeId", rootNode == null ? null : rootNode.getId());
        update(digest, "documentId", rootNode == null || rootNode.getDocument() == null ? null : rootNode.getDocument().getId());
        update(digest, "nodeKey", rootNode == null ? null : rootNode.getNodeKey());
        update(digest, "nodeType", rootNode == null ? null : rootNode.getNodeType());
        update(digest, "sectionPath", rootNode == null ? null : rootNode.getSectionPath());
        update(digest, "title", rootNode == null ? null : rootNode.getTitle());
        update(digest, "updatedAt", rootNode == null ? null : rootNode.getUpdatedAt());

        List<DocumentChunk> safeChunks = chunks == null ? List.of() : chunks;
        for (DocumentChunk chunk : safeChunks.stream()
                .sorted(Comparator
                        .comparingInt(this::sourceOrder)
                        .thenComparing(item -> item.getId() == null ? Long.MAX_VALUE : item.getId()))
                .toList()) {
            update(digest, "chunkId", chunk.getId());
            update(digest, "chunkIndex", chunk.getChunkIndex());
            update(digest, "sourceOrder", chunk.getSourceOrder());
            update(digest, "chunkType", chunk.getChunkType());
            update(digest, "pageFrom", chunk.getPageFrom());
            update(digest, "pageTo", chunk.getPageTo());
            update(digest, "updatedAt", chunk.getUpdatedAt());
            update(digest, "content", chunk.getContent());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is not available", ex);
        }
    }

    private void update(MessageDigest digest, String label, Object value) {
        digest.update(label.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '=');
        String stringValue = value instanceof LocalDateTime localDateTime
                ? localDateTime.toString()
                : String.valueOf(value);
        digest.update(stringValue.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }

    private int sourceOrder(DocumentChunk chunk) {
        if (chunk == null) {
            return Integer.MAX_VALUE;
        }
        if (chunk.getSourceOrder() != null) {
            return chunk.getSourceOrder();
        }
        if (chunk.getChunkIndex() != null) {
            return chunk.getChunkIndex();
        }
        return Integer.MAX_VALUE;
    }

    public record NodeScope(
            DocumentNode rootNode,
            List<DocumentChunk> chunks,
            String sourceHash
    ) {
    }
}
