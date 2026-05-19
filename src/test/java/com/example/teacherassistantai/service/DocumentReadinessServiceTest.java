package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentEnrichmentStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentReadinessServiceTest {

    @Test
    void refreshDocumentReadyStatus_countsSkippedChapterQuestionsAsProcessed() {
        Fixture fixture = fixture();
        DocumentReadinessService service = service(fixture);
        mockArtifacts(
                fixture,
                fixture.chapters(),
                List.of(fixture.chapters().get(0), fixture.chapters().get(1)),
                List.of(fixture.chapters().get(2))
        );

        service.refreshDocumentReadyStatus(fixture.document().getId());

        assertThat(fixture.document().getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(fixture.document().getEnrichmentStatus()).isEqualTo(DocumentEnrichmentStatus.PARTIAL_FAILED);
        assertThat(fixture.document().getEnrichmentError()).contains("3/4");
    }

    @Test
    void refreshDocumentReadyStatus_marksReadyAfterMaxRetriesWhenSummaryIsReady() {
        Fixture fixture = fixture();
        fixture.document().setEnrichmentRetryCount(fixture.ragProperties().getEnrichment().getAutoRetryMaxAttempts());
        DocumentReadinessService service = service(fixture);
        mockArtifacts(
                fixture,
                fixture.chapters(),
                List.of(fixture.chapters().get(0), fixture.chapters().get(1)),
                List.of()
        );

        service.refreshDocumentReadyStatus(fixture.document().getId());

        assertThat(fixture.document().getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(fixture.document().getEnrichmentStatus()).isEqualTo(DocumentEnrichmentStatus.PARTIAL_FAILED);
        assertThat(fixture.document().getEnrichmentError()).contains("after max retries");
    }

    private DocumentReadinessService service(Fixture fixture) {
        return new DocumentReadinessService(
                fixture.documentRepository(),
                fixture.nodeRepository(),
                fixture.artifactRepository(),
                fixture.ragProperties(),
                new TransactionTemplate(new NoOpTransactionManager())
        );
    }

    private Fixture fixture() {
        Document document = Document.builder()
                .title("Document")
                .status(DocumentStatus.SUMMARISING)
                .enrichmentStatus(DocumentEnrichmentStatus.RUNNING)
                .build();
        document.setId(10L);

        List<DocumentNode> chapters = List.of(
                chapter(document, 1L),
                chapter(document, 2L),
                chapter(document, 3L),
                chapter(document, 4L)
        );

        DocumentRepository documentRepository = mock(DocumentRepository.class);
        DocumentNodeRepository nodeRepository = mock(DocumentNodeRepository.class);
        DocumentNodeArtifactRepository artifactRepository = mock(DocumentNodeArtifactRepository.class);
        RagProperties ragProperties = new RagProperties();

        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(document.getId(), "chapter"))
                .thenReturn(chapters);

        return new Fixture(document, chapters, documentRepository, nodeRepository, artifactRepository, ragProperties);
    }

    private void mockArtifacts(Fixture fixture,
                               List<DocumentNode> summaryCompleted,
                               List<DocumentNode> questionCompleted,
                               List<DocumentNode> questionSkipped) {
        when(fixture.artifactRepository().findByNodeIdsAndArtifactTypeAndStatusOrderByNodeOrderAndUpdatedAt(
                any(),
                eq(DocumentNodeArtifactType.SUMMARY),
                eq(DocumentNodeArtifactStatus.COMPLETED)
        )).thenReturn(artifacts(summaryCompleted, DocumentNodeArtifactType.SUMMARY, DocumentNodeArtifactStatus.COMPLETED));
        when(fixture.artifactRepository().findByNodeIdsAndArtifactTypeAndStatusOrderByNodeOrderAndUpdatedAt(
                any(),
                eq(DocumentNodeArtifactType.REVIEW_QUESTION_SET),
                eq(DocumentNodeArtifactStatus.COMPLETED)
        )).thenReturn(artifacts(questionCompleted, DocumentNodeArtifactType.REVIEW_QUESTION_SET, DocumentNodeArtifactStatus.COMPLETED));
        when(fixture.artifactRepository().findByNodeIdsAndArtifactTypeAndStatusOrderByNodeOrderAndUpdatedAt(
                any(),
                eq(DocumentNodeArtifactType.REVIEW_QUESTION_SET),
                eq(DocumentNodeArtifactStatus.SKIPPED)
        )).thenReturn(artifacts(questionSkipped, DocumentNodeArtifactType.REVIEW_QUESTION_SET, DocumentNodeArtifactStatus.SKIPPED));
    }

    private List<DocumentNodeArtifact> artifacts(List<DocumentNode> nodes,
                                                 DocumentNodeArtifactType artifactType,
                                                 DocumentNodeArtifactStatus status) {
        return nodes.stream()
                .map(node -> DocumentNodeArtifact.builder()
                        .document(node.getDocument())
                        .documentNode(node)
                        .artifactType(artifactType)
                        .status(status)
                        .promptVersion("test")
                        .model("test")
                        .sourceHash("hash-" + artifactType + "-" + node.getId())
                        .build())
                .toList();
    }

    private DocumentNode chapter(Document document, Long id) {
        DocumentNode node = DocumentNode.builder()
                .document(document)
                .nodeType("chapter")
                .title("Chapter " + id)
                .orderIndex(id.intValue())
                .build();
        node.setId(id);
        return node;
    }

    private record Fixture(
            Document document,
            List<DocumentNode> chapters,
            DocumentRepository documentRepository,
            DocumentNodeRepository nodeRepository,
            DocumentNodeArtifactRepository artifactRepository,
            RagProperties ragProperties
    ) {
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
