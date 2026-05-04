package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DocumentHierarchyPersistenceService {

    private final DocumentNodeRepository documentNodeRepository;

    @Transactional
    public HierarchyPersistenceResult persist(Document document,
                                              MarkdownChunkingService.HierarchicalMarkdownDocument hierarchyDocument) {
        documentNodeRepository.deleteByDocumentId(document.getId());

        Map<String, DocumentNode> nodeByKey = new LinkedHashMap<>();
        List<DocumentNode> persisted = new ArrayList<>();
        OrderCounter orderCounter = new OrderCounter();

        persistNode(document, null, hierarchyDocument.root(), 0, orderCounter, nodeByKey, persisted);
        return new HierarchyPersistenceResult(Map.copyOf(nodeByKey), List.copyOf(persisted));
    }

    private void persistNode(Document document,
                             DocumentNode parent,
                             MarkdownChunkingService.PublicHierarchyNode source,
                             int level,
                             OrderCounter orderCounter,
                             Map<String, DocumentNode> nodeByKey,
                             List<DocumentNode> persisted) {
        DocumentNode node = DocumentNode.builder()
                .document(document)
                .parent(parent)
                .subjectId(document.getSubject().getId())
                .nodeKey(source.nodeId())
                .nodeType(source.nodeType())
                .level(level)
                .title(source.title())
                .sectionPath(toSectionPath(source.breadcrumb()))
                .orderIndex(orderCounter.next())
                .pageFrom(source.pageFrom())
                .pageTo(source.pageTo())
                .content(null)
                .metadataJsonb(metadata(source))
                .build();

        DocumentNode saved = documentNodeRepository.save(node);
        nodeByKey.put(saved.getNodeKey(), saved);
        persisted.add(saved);

        for (MarkdownChunkingService.PublicHierarchyNode child : source.children()) {
            persistNode(document, saved, child, level + 1, orderCounter, nodeByKey, persisted);
        }
    }

    private Map<String, Object> metadata(MarkdownChunkingService.PublicHierarchyNode node) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceNodeKey", node.nodeId());
        metadata.put("sourceParentNodeKey", node.parentNodeId());
        metadata.put("breadcrumb", node.breadcrumb());
        metadata.put("charStart", node.charStart());
        metadata.put("charEnd", node.charEnd());
        metadata.put("contentCharCount", node.contentCharCount());
        metadata.put("hierarchical", true);
        return metadata;
    }

    private String toSectionPath(List<String> breadcrumb) {
        if (breadcrumb == null || breadcrumb.isEmpty()) {
            return null;
        }
        String value = String.join(" > ", breadcrumb);
        return StringUtils.hasText(value) ? value : null;
    }

    private static final class OrderCounter {
        private int value = 0;

        private int next() {
            return value++;
        }
    }

    public record HierarchyPersistenceResult(
            Map<String, DocumentNode> nodeByKey,
            List<DocumentNode> nodes
    ) {
    }
}
