package com.example.teacherassistantai.service;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.integration.ai.AiEmbeddingGateway;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DocumentChunkIngestionServiceHierarchyTest {

    @Test
    void ingest_setsDocumentNodeForeignKeysAndHierarchyColumns() {
        DocumentChunkRepository chunkRepository = mock(DocumentChunkRepository.class);
        doAnswer(invocation -> {
            DocumentChunk chunk = invocation.getArgument(0);
            chunk.setId(99L);
            return chunk;
        }).when(chunkRepository).save(any(DocumentChunk.class));

        AiEmbeddingGateway embeddingGateway = mock(AiEmbeddingGateway.class);
        List<Double> fakeEmbedding = IntStream.range(0, 4).mapToObj(i -> 0.1d).toList();
        org.mockito.Mockito.when(embeddingGateway.embedAll(any()))
                .thenAnswer(inv -> {
                    List<String> inputs = inv.getArgument(0);
                    return inputs.stream().map(t -> fakeEmbedding).toList();
                });

        RagProperties ragProperties = new RagProperties();
        ragProperties.setEmbeddingDimensions(4);
        ragProperties.setEmbeddingBatchSize(64);
        ragProperties.setEmbeddingConcurrency(1);

        DocumentChunkIngestionService service = new DocumentChunkIngestionService(
                new MarkdownChunkingService(),
                new ChunkMetadataBuilder(),
                embeddingGateway,
                chunkRepository,
                ragProperties,
                mock(org.springframework.transaction.PlatformTransactionManager.class)
        );

        Subject subject = Subject.builder().name("Subject").code("SUB").build();
        subject.setId(7L);
        Document document = Document.builder()
                .title("Document")
                .subject(subject)
                .build();
        document.setId(10L);

        MarkdownChunkingService.HierarchicalMarkdownDocument hierarchyDocument =
                new MarkdownChunkingService().parseHierarchicalDocument("""
                        ### Chương 1: Tổng quan

                        #### I. Khái niệm

                        Nội dung chính.
                        """);
        DocumentNode chapter = node("n1");
        DocumentNode section = node("n2");

        List<DocumentChunk> chunks = service.ingest(document, hierarchyDocument, Map.of(
                "n1", chapter,
                "n2", section
        ));

        assertThat(chunks).hasSize(1);

        ArgumentCaptor<DocumentChunk> chunkCaptor = ArgumentCaptor.forClass(DocumentChunk.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> embeddingInputsCaptor = ArgumentCaptor.forClass(List.class);
        verify(chunkRepository).save(chunkCaptor.capture());
        verify(embeddingGateway).embedAll(embeddingInputsCaptor.capture());
        verify(chunkRepository).updateEmbeddingLiteral(eq(99L), eq("[0.1,0.1,0.1,0.1]"));

        DocumentChunk saved = chunkCaptor.getValue();
        assertThat(saved.getNode()).isSameAs(section);
        assertThat(saved.getParentNode()).isSameAs(chapter);
        assertThat(saved.getSectionPath()).isEqualTo("Chương 1: Tổng quan > I. Khái niệm");
        assertThat(saved.getSourceOrder()).isEqualTo(1);
        assertThat(saved.getPageFrom()).isNull();
        assertThat(saved.getContent())
                .contains("Chương 1: Tổng quan > I. Khái niệm")
                .contains("Nội dung chính.");
        assertThat(saved.getEmbedText())
                .isEqualTo("Nội dung chính.")
                .doesNotContain("Chương 1")
                .doesNotContain(" > ");
        assertThat(embeddingInputsCaptor.getValue()).containsExactly("Nội dung chính.");
        assertThat(saved.getMetadataJsonb()).containsEntry("nodeId", "n2");
        assertThat(saved.getMetadataJsonb()).containsEntry("parentNodeId", "n1");
    }

    private DocumentNode node(String nodeKey) {
        DocumentNode node = DocumentNode.builder()
                .nodeKey(nodeKey)
                .nodeType("section")
                .level(1)
                .orderIndex(1)
                .subjectId(7L)
                .build();
        node.setId(Long.parseLong(nodeKey.substring(1)));
        return node;
    }
}
