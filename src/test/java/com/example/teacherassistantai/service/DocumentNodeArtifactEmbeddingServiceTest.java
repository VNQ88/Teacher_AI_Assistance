package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.integration.ai.AiEmbeddingGateway;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentNodeArtifactEmbeddingServiceTest {

    @Mock
    private DocumentNodeArtifactRepository artifactRepository;

    @Mock
    private AiEmbeddingGateway embeddingGateway;

    private RagProperties ragProperties;

    private DocumentNodeArtifactEmbeddingService service;

    @BeforeEach
    void setUp() {
        ragProperties = new RagProperties();
        ragProperties.setEmbeddingDimensions(4);
        service = new DocumentNodeArtifactEmbeddingService(
                artifactRepository,
                embeddingGateway,
                ragProperties,
                new TransactionTemplate(new NoOpTransactionManager())
        );
        lenient().when(embeddingGateway.embeddingModel()).thenReturn("embed-v1");
    }

    @Test
    void embedCompletedSummaryArtifact_buildsRetrievalTextAndPersistsEmbedding() {
        DocumentNodeArtifact artifact = completedSummaryArtifact();
        when(artifactRepository.findCompletedSummaryForRetrievalEmbedding(44L))
                .thenReturn(Optional.of(artifact));
        when(artifactRepository.findRetrievalEmbeddingState(44L))
                .thenReturn(Optional.empty());
        when(embeddingGateway.embed(anyString()))
                .thenReturn(List.of(0.1, 0.2, 0.3, 0.4));
        when(artifactRepository.updateRetrievalEmbedding(
                eq(44L), anyString(), anyString(), anyString(), eq("embed-v1"), eq(4)))
                .thenReturn(1);

        boolean embedded = service.embedCompletedSummaryArtifact(44L);

        assertThat(embedded).isTrue();

        ArgumentCaptor<String> embeddingInputCaptor = ArgumentCaptor.forClass(String.class);
        verify(embeddingGateway).embed(embeddingInputCaptor.capture());
        String retrievalText = embeddingInputCaptor.getValue();
        assertThat(retrievalText)
                .contains("Document: Giáo trình")
                .contains("Path: Chương 1 > Mục 1")
                .contains("Node type: section")
                .contains("Title: Mục 1")
                .contains("Summary: Nội dung chính")
                .contains("- Ý 1")
                .contains("- Tiểu mục: Nội dung tiểu mục");

        verify(artifactRepository).updateRetrievalEmbedding(
                eq(44L),
                eq(retrievalText),
                eq(service.retrievalTextHash(retrievalText)),
                eq("[0.1,0.2,0.3,0.4]"),
                eq("embed-v1"),
                eq(4)
        );
    }

    @Test
    void embedCompletedSummaryArtifact_skipsWhenStoredEmbeddingIsCurrent() {
        DocumentNodeArtifact artifact = completedSummaryArtifact();
        String retrievalText = service.buildRetrievalText(artifact);
        String hash = service.retrievalTextHash(retrievalText);
        when(artifactRepository.findCompletedSummaryForRetrievalEmbedding(44L))
                .thenReturn(Optional.of(artifact));
        when(artifactRepository.findRetrievalEmbeddingState(44L))
                .thenReturn(Optional.of(state(hash, "embed-v1", 4, true)));

        boolean embedded = service.embedCompletedSummaryArtifact(44L);

        assertThat(embedded).isTrue();
        verify(embeddingGateway, never()).embed(anyString());
        verify(artifactRepository, never()).updateRetrievalEmbedding(
                eq(44L), anyString(), anyString(), anyString(), anyString(), eq(4));
    }

    @Test
    void embedCompletedSummaryArtifact_leavesEmbeddingNullWhenDimensionMismatch() {
        DocumentNodeArtifact artifact = completedSummaryArtifact();
        when(artifactRepository.findCompletedSummaryForRetrievalEmbedding(44L))
                .thenReturn(Optional.of(artifact));
        when(artifactRepository.findRetrievalEmbeddingState(44L))
                .thenReturn(Optional.empty());
        when(embeddingGateway.embed(anyString()))
                .thenReturn(List.of(0.1, 0.2));

        boolean embedded = service.embedCompletedSummaryArtifact(44L);

        assertThat(embedded).isFalse();
        verify(artifactRepository, never()).updateRetrievalEmbedding(
                eq(44L), anyString(), anyString(), anyString(), anyString(), eq(4));
    }

    @Test
    void embedCompletedSummaryArtifact_acceptsConfigured1024Dimension() {
        ragProperties.setEmbeddingDimensions(1024);
        DocumentNodeArtifact artifact = completedSummaryArtifact();
        when(artifactRepository.findCompletedSummaryForRetrievalEmbedding(44L))
                .thenReturn(Optional.of(artifact));
        when(artifactRepository.findRetrievalEmbeddingState(44L))
                .thenReturn(Optional.empty());
        when(embeddingGateway.embed(anyString()))
                .thenReturn(vector1024());
        when(artifactRepository.updateRetrievalEmbedding(
                eq(44L), anyString(), anyString(), anyString(), eq("embed-v1"), eq(1024)))
                .thenReturn(1);

        boolean embedded = service.embedCompletedSummaryArtifact(44L);

        assertThat(embedded).isTrue();
        verify(artifactRepository).updateRetrievalEmbedding(
                eq(44L), anyString(), anyString(), anyString(), eq("embed-v1"), eq(1024));
    }

    @Test
    void buildRetrievalText_handlesMissingSummaryFieldsSafely() {
        Document document = Document.builder()
                .title("Giáo trình thiếu field")
                .build();
        DocumentNode node = DocumentNode.builder()
                .document(document)
                .nodeType("chapter")
                .title("Chương thiếu summary")
                .build();
        DocumentNodeArtifact artifact = DocumentNodeArtifact.builder()
                .document(document)
                .documentNode(node)
                .contentJsonb(Map.of())
                .build();

        String retrievalText = service.buildRetrievalText(artifact);

        assertThat(retrievalText)
                .contains("Document: Giáo trình thiếu field")
                .contains("Node type: chapter")
                .contains("Title: Chương thiếu summary")
                .doesNotContain("Summary:")
                .doesNotContain("Key points:");
    }

    @Test
    void retrievalTextHash_isDeterministic() {
        String first = service.retrievalTextHash("same retrieval text");
        String second = service.retrievalTextHash("same retrieval text");
        String different = service.retrievalTextHash("changed retrieval text");

        assertThat(first).hasSize(64);
        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEqualTo(different);
    }

    @Test
    void backfillCompletedSummaryEmbeddings_embedsMissingSummaryArtifacts() {
        DocumentNodeArtifact artifact = completedSummaryArtifact();
        when(artifactRepository.findCompletedSummaryIdsNeedingRetrievalEmbedding(null, null, "embed-v1", 4, 2))
                .thenReturn(List.of(44L, 45L));
        when(artifactRepository.findCompletedSummaryForRetrievalEmbedding(44L))
                .thenReturn(Optional.of(artifact));
        when(artifactRepository.findCompletedSummaryForRetrievalEmbedding(45L))
                .thenReturn(Optional.of(artifact));
        when(artifactRepository.findRetrievalEmbeddingState(44L))
                .thenReturn(Optional.empty());
        when(artifactRepository.findRetrievalEmbeddingState(45L))
                .thenReturn(Optional.empty());
        when(embeddingGateway.embed(anyString()))
                .thenReturn(List.of(0.1, 0.2, 0.3, 0.4));
        when(artifactRepository.updateRetrievalEmbedding(
                org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString(), anyString(), eq("embed-v1"), eq(4)))
                .thenReturn(1);

        int embedded = service.backfillCompletedSummaryEmbeddings(2);

        assertThat(embedded).isEqualTo(2);
    }

    @Test
    void backfillCompletedSummaryEmbeddings_shouldScopeByDocumentAndSubject() {
        when(artifactRepository.findCompletedSummaryIdsNeedingRetrievalEmbedding(10L, 7L, "embed-v1", 4, 3))
                .thenReturn(List.of());

        int embedded = service.backfillCompletedSummaryEmbeddings(10L, 7L, 3);

        assertThat(embedded).isZero();
        verify(artifactRepository).findCompletedSummaryIdsNeedingRetrievalEmbedding(10L, 7L, "embed-v1", 4, 3);
    }

    @Test
    void retrievalEmbeddingCoverage_returnsCountsForCurrentModelAndDimensions() {
        when(artifactRepository.retrievalEmbeddingCoverageStats(10L, 7L, "embed-v1", 4))
                .thenReturn(stats(12L, 8L, 4L));

        DocumentNodeArtifactEmbeddingService.RetrievalEmbeddingCoverage coverage =
                service.retrievalEmbeddingCoverage(10L, 7L);

        assertThat(coverage.totalCompletedSummaries()).isEqualTo(12);
        assertThat(coverage.embeddedCurrent()).isEqualTo(8);
        assertThat(coverage.pending()).isEqualTo(4);
        assertThat(coverage.embeddingModel()).isEqualTo("embed-v1");
        assertThat(coverage.embeddingDimensions()).isEqualTo(4);
    }

    private DocumentNodeArtifact completedSummaryArtifact() {
        Document document = Document.builder()
                .title("Giáo trình")
                .build();
        document.setId(10L);

        DocumentNode node = DocumentNode.builder()
                .document(document)
                .nodeType("section")
                .title("Mục 1")
                .sectionPath("Chương 1 > Mục 1")
                .orderIndex(1)
                .build();
        node.setId(20L);

        DocumentNodeArtifact artifact = DocumentNodeArtifact.builder()
                .document(document)
                .documentNode(node)
                .artifactType(DocumentNodeArtifactType.SUMMARY)
                .status(DocumentNodeArtifactStatus.COMPLETED)
                .promptVersion("enrichment-v2")
                .model("summary-model")
                .sourceHash("source-hash")
                .contentJsonb(Map.of(
                        "summary", "Nội dung chính\ncủa mục.",
                        "keyPoints", List.of("Ý 1", "Ý 2"),
                        "childSummaries", List.of(Map.of(
                                "title", "Tiểu mục",
                                "summary", "Nội dung tiểu mục"
                        ))
                ))
                .build();
        artifact.setId(44L);
        return artifact;
    }

    private List<Double> vector1024() {
        return IntStream.range(0, 1024)
                .mapToObj(i -> 0.01d)
                .toList();
    }

    private DocumentNodeArtifactRepository.RetrievalEmbeddingState state(String retrievalTextHash,
                                                                         String embeddingModel,
                                                                         Integer embeddingDimensions,
                                                                         Boolean hasEmbedding) {
        return new DocumentNodeArtifactRepository.RetrievalEmbeddingState() {
            @Override
            public String getRetrievalTextHash() {
                return retrievalTextHash;
            }

            @Override
            public String getEmbeddingModel() {
                return embeddingModel;
            }

            @Override
            public Integer getEmbeddingDimensions() {
                return embeddingDimensions;
            }

            @Override
            public Boolean getHasEmbedding() {
                return hasEmbedding;
            }
        };
    }

    private DocumentNodeArtifactRepository.RetrievalEmbeddingCoverageStats stats(Long total,
                                                                                 Long embedded,
                                                                                 Long pending) {
        return new DocumentNodeArtifactRepository.RetrievalEmbeddingCoverageStats() {
            @Override
            public Long getTotalCompletedSummaries() {
                return total;
            }

            @Override
            public Long getEmbeddedCurrent() {
                return embedded;
            }

            @Override
            public Long getPending() {
                return pending;
            }
        };
    }

    private static class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
