package com.example.teacherassistantai.service;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.integration.gemini.GeminiEmbeddingGateway;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DocumentChunkIngestionService {

    private final MarkdownChunkingService markdownChunkingService;
    private final ChunkMetadataBuilder chunkMetadataBuilder;
    private final GeminiEmbeddingGateway embeddingGateway;
    private final DocumentChunkRepository documentChunkRepository;
    private final RagProperties ragProperties;

    @Transactional
    public List<DocumentChunk> ingest(Document document, String markdown) {
        MarkdownChunkingService.HierarchicalMarkdownDocument hierarchyDocument =
                markdownChunkingService.parseHierarchicalDocument(markdown);
        return ingest(document, hierarchyDocument, Map.of());
    }

    @Transactional
    public List<DocumentChunk> ingest(Document document,
                                      MarkdownChunkingService.HierarchicalMarkdownDocument hierarchyDocument,
                                      Map<String, DocumentNode> nodeByKey) {
        List<HierarchicalMarkdownChunk> chunks = hierarchyDocument.chunks();
        List<DocumentChunk> persisted = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            HierarchicalMarkdownChunk hierarchicalChunk = chunks.get(i);
            String content = hierarchicalChunk.content();
            DocumentNode node = resolveNode(nodeByKey, hierarchicalChunk.nodeId(), "nodeId");
            DocumentNode parentNode = resolveNode(nodeByKey, hierarchicalChunk.parentNodeId(), "parentNodeId");
            DocumentChunk chunk = DocumentChunk.builder()
                    .document(document)
                    .subjectId(document.getSubject().getId())
                    .node(node)
                    .parentNode(parentNode)
                    .chunkIndex(i + 1)
                    .sourceOrder(i + 1)
                    .chunkType(hierarchicalChunk.chunkType())
                    .sectionPath(String.join(" > ", hierarchicalChunk.breadcrumb()))
                    .pageFrom(hierarchicalChunk.pageFrom())
                    .pageTo(hierarchicalChunk.pageTo())
                    .content(content)
                    .tokenCount(Math.max(1, content.length() / 4))
                    .metadataJsonb(chunkMetadataBuilder.buildHierarchicalJsonb(
                            hierarchicalChunk.pageFrom(),
                            hierarchicalChunk.pageTo(),
                            hierarchicalChunk.sectionHeader(),
                            hierarchicalChunk.chunkType(),
                            content.length(),
                            hierarchicalChunk.nodeType(),
                            hierarchicalChunk.nodeId(),
                            hierarchicalChunk.parentNodeId(),
                            hierarchicalChunk.breadcrumb(),
                            hierarchicalChunk.charStart(),
                            hierarchicalChunk.charEnd()))
                    .build();

            DocumentChunk saved = documentChunkRepository.save(chunk);
            List<Double> embedding = embeddingGateway.embed(content);
            validateEmbeddingDimensions(embedding, saved.getId());
            String embeddingLiteral = toVectorLiteral(embedding);
            documentChunkRepository.updateEmbeddingLiteral(saved.getId(), embeddingLiteral);
            persisted.add(saved);
        }

        return persisted;
    }

    @Transactional
    public void deleteExistingChunks(Long documentId) {
        documentChunkRepository.deleteMessageSourceLinksByDocumentId(documentId);
        documentChunkRepository.deleteByDocumentId(documentId);
    }

    private DocumentNode resolveNode(Map<String, DocumentNode> nodeByKey, String nodeKey, String fieldName) {
        if (nodeKey == null || nodeKey.isBlank()) {
            return null;
        }
        if (nodeByKey == null || nodeByKey.isEmpty()) {
            return null;
        }
        DocumentNode node = nodeByKey.get(nodeKey);
        if (node == null) {
            throw new InvalidDataException("Cannot resolve %s '%s' to persisted document_nodes row"
                    .formatted(fieldName, nodeKey));
        }
        return node;
    }

    private void validateEmbeddingDimensions(List<Double> embedding, Long chunkId) {
        int actual = embedding == null ? 0 : embedding.size();
        int expected = ragProperties.getEmbeddingDimensions();
        if (actual != expected) {
            throw new InvalidDataException("Embedding dimension mismatch for chunk %d: expected %d, got %d"
                    .formatted(chunkId, expected, actual));
        }
    }

    public String toVectorLiteral(List<Double> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) builder.append(',');
            builder.append(values.get(i));
        }
        builder.append(']');
        return builder.toString();
    }
}
