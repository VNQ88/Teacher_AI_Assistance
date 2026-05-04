package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.SubjectType;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DocumentHierarchyPersistenceServiceTest {

    @Test
    void persist_insertsFullTreeInPreOrder() {
        DocumentNodeRepository repository = mock(DocumentNodeRepository.class);
        AtomicLong idSequence = new AtomicLong(1);
        doAnswer(invocation -> {
            DocumentNode node = invocation.getArgument(0);
            node.setId(idSequence.getAndIncrement());
            return node;
        }).when(repository).save(any(DocumentNode.class));

        DocumentHierarchyPersistenceService service = new DocumentHierarchyPersistenceService(repository);
        MarkdownChunkingService.HierarchicalMarkdownDocument hierarchyDocument =
                new MarkdownChunkingService().parseHierarchicalDocument("""
                        ### Chương 1: Tổng quan

                        #### I. Khái niệm

                        Nội dung chính.
                        """);

        Subject subject = Subject.builder()
                .name("Pháp luật")
                .code("LAW")
                .subjectType(SubjectType.TEXT_BASED)
                .build();
        subject.setId(5L);
        Document document = Document.builder()
                .title("Document")
                .subject(subject)
                .build();
        document.setId(100L);

        DocumentHierarchyPersistenceService.HierarchyPersistenceResult result =
                service.persist(document, hierarchyDocument);

        verify(repository).deleteByDocumentId(100L);
        assertThat(result.nodes()).hasSize(3);
        assertThat(result.nodeByKey()).containsKey("n0");

        DocumentNode root = result.nodeByKey().get("n0");
        assertThat(root.getParent()).isNull();
        assertThat(root.getLevel()).isZero();
        assertThat(root.getOrderIndex()).isZero();

        DocumentNode chapter = result.nodeByKey().get("n1");
        assertThat(chapter.getParent()).isSameAs(root);
        assertThat(chapter.getNodeKey()).isEqualTo("n1");
        assertThat(chapter.getSectionPath()).isEqualTo("Chương 1: Tổng quan");
        assertThat(chapter.getMetadataJsonb()).containsEntry("sourceNodeKey", "n1");
    }
}
