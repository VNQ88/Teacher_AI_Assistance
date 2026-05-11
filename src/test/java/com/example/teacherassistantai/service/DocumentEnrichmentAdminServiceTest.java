package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentEnrichmentStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import com.example.teacherassistantai.dto.request.DocumentEnrichmentRequest;
import com.example.teacherassistantai.dto.response.DocumentEnrichmentJobResponse;
import com.example.teacherassistantai.dto.response.DocumentNodeArtifactResponse;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentEnrichmentAdminServiceTest {

    private DocumentRepository documentRepository;
    private DocumentNodeRepository documentNodeRepository;
    private DocumentNodeArtifactRepository artifactRepository;
    private DocumentEnrichmentService enrichmentService;
    private DocumentEnrichmentAdminService adminService;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        documentNodeRepository = mock(DocumentNodeRepository.class);
        artifactRepository = mock(DocumentNodeArtifactRepository.class);
        enrichmentService = mock(DocumentEnrichmentService.class);
        adminService = new DocumentEnrichmentAdminService(
                documentRepository,
                documentNodeRepository,
                artifactRepository,
                enrichmentService
        );
    }

    @Test
    void getDocumentArtifacts_mapsArtifactAndNodeMetadata() {
        Document document = document(DocumentStatus.READY);
        DocumentNode node = node(document);
        DocumentNodeArtifact artifact = DocumentNodeArtifact.builder()
                .document(document)
                .documentNode(node)
                .artifactType(DocumentNodeArtifactType.SUMMARY)
                .status(DocumentNodeArtifactStatus.COMPLETED)
                .promptVersion("enrichment-v1")
                .model("openai-gpt-oss-120b")
                .sourceHash("hash")
                .tokenCount(123)
                .contentJsonb(Map.of("summary", "Tóm tắt"))
                .build();
        artifact.setId(300L);

        when(documentRepository.findById(10L)).thenReturn(Optional.of(document));
        when(artifactRepository.findByDocumentIdOrderByNodeOrderAndArtifactType(10L)).thenReturn(List.of(artifact));

        List<DocumentNodeArtifactResponse> responses = adminService.getDocumentArtifacts(10L);

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().getDocumentId()).isEqualTo(10L);
        assertThat(responses.getFirst().getDocumentNodeId()).isEqualTo(100L);
        assertThat(responses.getFirst().getNodeTitle()).isEqualTo("Chương 1");
        assertThat(responses.getFirst().getArtifactType()).isEqualTo(DocumentNodeArtifactType.SUMMARY);
        assertThat(responses.getFirst().getStatus()).isEqualTo(DocumentNodeArtifactStatus.COMPLETED);
    }

    @Test
    void enrichDocument_marksQueuedAndDispatchesAsyncService() {
        Document document = document(DocumentStatus.READY);
        when(documentRepository.findById(10L)).thenReturn(Optional.of(document));
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DocumentEnrichmentRequest request = new DocumentEnrichmentRequest();
        request.setForceRegenerate(true);
        request.setArtifactTypes(List.of(DocumentNodeArtifactType.SUMMARY));

        DocumentEnrichmentJobResponse response = adminService.enrichDocument(10L, request);

        assertThat(response.getDocumentStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(response.getEnrichmentStatus()).isEqualTo(DocumentEnrichmentStatus.QUEUED);
        assertThat(response.getArtifactTypes()).containsExactly(DocumentNodeArtifactType.SUMMARY);
        verify(enrichmentService).enqueueDocumentEnrichment(10L, true, List.of(DocumentNodeArtifactType.SUMMARY));
    }

    @Test
    void enrichNode_rejectsNodeOutsideDocument() {
        Document document = document(DocumentStatus.READY);
        document.setId(99L);
        DocumentNode node = node(document);
        when(documentNodeRepository.findById(100L)).thenReturn(Optional.of(node));

        assertThatThrownBy(() -> adminService.enrichNode(10L, 100L, null))
                .isInstanceOf(InvalidDataException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void deleteArtifacts_deletesSelectedTypesAndResetsFullUseDocument() {
        Document document = document(DocumentStatus.READY);
        when(documentRepository.findById(10L)).thenReturn(Optional.of(document));
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));
        DocumentEnrichmentRequest request = new DocumentEnrichmentRequest();
        request.setArtifactTypes(List.of(DocumentNodeArtifactType.REVIEW_QUESTION_SET));

        adminService.deleteArtifacts(10L, request);

        verify(artifactRepository).deleteByDocumentIdAndArtifactTypeIn(
                eq(10L),
                eq(List.of(DocumentNodeArtifactType.REVIEW_QUESTION_SET))
        );
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(document.getEnrichmentStatus()).isEqualTo(DocumentEnrichmentStatus.NOT_STARTED);
        assertThat(document.getEnrichmentError()).isNull();
    }

    private Document document(DocumentStatus status) {
        Document document = Document.builder()
                .title("Document")
                .status(status)
                .enrichmentStatus(DocumentEnrichmentStatus.ENRICHED)
                .build();
        document.setId(10L);
        return document;
    }

    private DocumentNode node(Document document) {
        DocumentNode node = DocumentNode.builder()
                .document(document)
                .nodeType("chapter")
                .title("Chương 1")
                .sectionPath("Chương 1")
                .orderIndex(1)
                .build();
        node.setId(100L);
        return node;
    }
}
