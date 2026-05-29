package com.example.teacherassistantai.service;

import com.example.teacherassistantai.dto.response.DocumentChunkDebugResponse;
import com.example.teacherassistantai.dto.response.DocumentHierarchyDebugResponse;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentDebugServiceTest {

    @Test
    void getHierarchy_shouldReconstructTreeFromFlatNodes() {
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        DocumentNodeRepository nodeRepository = mock(DocumentNodeRepository.class);
        DocumentChunkRepository chunkRepository = mock(DocumentChunkRepository.class);
        DocumentDebugService service = new DocumentDebugService(documentRepository, nodeRepository, chunkRepository);

        Document document = Document.builder().title("Document").build();
        document.setId(10L);
        DocumentNode root = node(1L, "n0", null, "document", "Document", 0);
        DocumentNode chapter = node(2L, "n1", root, "chapter", "Chuong 1", 1);

        when(documentRepository.findById(10L)).thenReturn(Optional.of(document));
        when(nodeRepository.findByDocumentIdOrderByOrderIndexAsc(10L)).thenReturn(List.of(root, chapter));

        DocumentHierarchyDebugResponse response = service.getHierarchy(10L);

        assertThat(response.getDocumentId()).isEqualTo(10L);
        assertThat(response.getNodeCount()).isEqualTo(2);
        assertThat(response.getRoots()).hasSize(1);
        assertThat(response.getRoots().getFirst().getNodeKey()).isEqualTo("n0");
        assertThat(response.getRoots().getFirst().getChildren()).hasSize(1);
        assertThat(response.getRoots().getFirst().getChildren().getFirst().getNodeKey()).isEqualTo("n1");
    }

    @Test
    void getChunks_shouldMapHierarchyAndOffsetMetadata() {
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        DocumentNodeRepository nodeRepository = mock(DocumentNodeRepository.class);
        DocumentChunkRepository chunkRepository = mock(DocumentChunkRepository.class);
        DocumentDebugService service = new DocumentDebugService(documentRepository, nodeRepository, chunkRepository);

        Document document = Document.builder().title("Document").build();
        document.setId(10L);
        DocumentNode parent = node(1L, "n1", null, "chapter", "Chuong 1", 1);
        DocumentNode node = node(2L, "n2", parent, "section", "I. Khai niem", 2);
        DocumentChunk chunk = DocumentChunk.builder()
                .chunkIndex(1)
                .sourceOrder(1)
                .chunkType("TEXT")
                .node(node)
                .parentNode(parent)
                .sectionPath("Chuong 1 > I. Khai niem")
                .pageFrom(3)
                .pageTo(4)
                .tokenCount(20)
                .metadataJsonb(Map.of("charStart", 100, "charEnd", 250))
                .content("Noi dung debug chunk.")
                .build();
        chunk.setId(99L);

        when(documentRepository.findById(10L)).thenReturn(Optional.of(document));
        when(chunkRepository.findByDocumentIdAndChunkTypeOrderBySourceOrderAsc(10L, "TEXT"))
                .thenReturn(List.of(chunk));

        List<DocumentChunkDebugResponse> response = service.getChunks(10L, "text");

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().getId()).isEqualTo(99L);
        assertThat(response.getFirst().getNodeKey()).isEqualTo("n2");
        assertThat(response.getFirst().getParentNodeKey()).isEqualTo("n1");
        assertThat(response.getFirst().getCharStart()).isEqualTo(100);
        assertThat(response.getFirst().getCharEnd()).isEqualTo(250);
    }

    private DocumentNode node(Long id, String nodeKey, DocumentNode parent, String nodeType, String title, int orderIndex) {
        DocumentNode node = DocumentNode.builder()
                .nodeKey(nodeKey)
                .parent(parent)
                .nodeType(nodeType)
                .title(title)
                .level(orderIndex)
                .orderIndex(orderIndex)
                .subjectId(1L)
                .metadataJsonb(Map.of("contentCharCount", 10, "charStart", 0, "charEnd", 10))
                .build();
        node.setId(id);
        return node;
    }
}
