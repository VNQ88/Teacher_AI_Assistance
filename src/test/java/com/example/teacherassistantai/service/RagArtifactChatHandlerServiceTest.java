package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagArtifactChatHandlerServiceTest {

    @Test
    void handle_returnsSummaryArtifactAndSourceChunks() {
        RagScopeResolverService scopeResolverService = mock(RagScopeResolverService.class);
        DocumentNodeArtifactRepository artifactRepository = mock(DocumentNodeArtifactRepository.class);
        DocumentChunkRepository chunkRepository = mock(DocumentChunkRepository.class);
        DocumentEnrichmentService documentEnrichmentService = mock(DocumentEnrichmentService.class);
        RagArtifactChatHandlerService handlerService = new RagArtifactChatHandlerService(
                scopeResolverService,
                artifactRepository,
                chunkRepository,
                documentEnrichmentService
        );
        DocumentNode node = node();
        DocumentChunk chunk = chunk();
        DocumentNodeArtifact artifact = DocumentNodeArtifact.builder()
                .documentNode(node)
                .artifactType(DocumentNodeArtifactType.SUMMARY)
                .status(DocumentNodeArtifactStatus.COMPLETED)
                .contentJsonb(Map.of(
                        "summary", "Nội dung tóm tắt đã precompute.",
                        "citations", List.of(Map.of("chunkId", 200L, "pageFrom", 3, "pageTo", 4))
                ))
                .build();

        when(scopeResolverService.resolve(any(), eq("Tóm tắt Chương 1"))).thenReturn(Optional.of(node));
        when(artifactRepository.findLatestByNodeTypeAndStatus(100L, DocumentNodeArtifactType.SUMMARY, DocumentNodeArtifactStatus.COMPLETED))
                .thenReturn(List.of(artifact));
        when(chunkRepository.findAllById(any())).thenReturn(List.of(chunk));

        RagArtifactChatHandlerService.ArtifactChatResult result =
                handlerService.handle(new ChatSession(), "Tóm tắt Chương 1", RagChatIntent.SECTION_SUMMARY);

        assertThat(result.artifactHit()).isTrue();
        assertThat(result.answer()).contains("Tóm tắt Chương 1");
        assertThat(result.answer()).contains("Nội dung tóm tắt đã precompute.");
        assertThat(result.sources()).containsExactly(chunk);
    }

    @Test
    void handle_rendersPartSummaryWithKeyPointsAndChildSummaries() {
        RagScopeResolverService scopeResolverService = mock(RagScopeResolverService.class);
        DocumentNodeArtifactRepository artifactRepository = mock(DocumentNodeArtifactRepository.class);
        DocumentChunkRepository chunkRepository = mock(DocumentChunkRepository.class);
        DocumentEnrichmentService documentEnrichmentService = mock(DocumentEnrichmentService.class);
        RagArtifactChatHandlerService handlerService = new RagArtifactChatHandlerService(
                scopeResolverService,
                artifactRepository,
                chunkRepository,
                documentEnrichmentService
        );
        DocumentNode node = partNode();
        DocumentNodeArtifact artifact = DocumentNodeArtifact.builder()
                .documentNode(node)
                .artifactType(DocumentNodeArtifactType.SUMMARY)
                .status(DocumentNodeArtifactStatus.COMPLETED)
                .contentJsonb(Map.of(
                        "summaryMode", "PART_FROM_CHAPTERS",
                        "summary", "Tổng quan phần này.",
                        "keyPoints", List.of("Ý chính A", "Ý chính B"),
                        "childSummaries", List.of(
                                Map.of(
                                        "title", "Chương 1",
                                        "summary", "Nội dung ngắn của chương 1."
                                ),
                                Map.of(
                                        "title", "Chương 2",
                                        "summary", "Nội dung ngắn của chương 2."
                                )
                        ),
                        "citations", List.of()
                ))
                .build();

        when(scopeResolverService.resolve(any(), eq("Tóm tắt Phần I"))).thenReturn(Optional.of(node));
        when(artifactRepository.findLatestByNodeTypeAndStatus(300L, DocumentNodeArtifactType.SUMMARY, DocumentNodeArtifactStatus.COMPLETED))
                .thenReturn(List.of(artifact));

        RagArtifactChatHandlerService.ArtifactChatResult result =
                handlerService.handle(new ChatSession(), "Tóm tắt Phần I", RagChatIntent.SECTION_SUMMARY);

        assertThat(result.artifactHit()).isTrue();
        assertThat(result.answer()).contains("Tổng quan phần này.");
        assertThat(result.answer()).contains("Các ý chính:");
        assertThat(result.answer()).contains("Ý chính A");
        assertThat(result.answer()).contains("Các nội dung chính:");
        assertThat(result.answer()).contains("Chương 1: Nội dung ngắn của chương 1.");
        assertThat(result.answer()).contains("Chương 2: Nội dung ngắn của chương 2.");
    }

    @Test
    void handle_returnsClearFallbackWhenArtifactIsMissing() {
        RagScopeResolverService scopeResolverService = mock(RagScopeResolverService.class);
        DocumentNodeArtifactRepository artifactRepository = mock(DocumentNodeArtifactRepository.class);
        DocumentEnrichmentService documentEnrichmentService = mock(DocumentEnrichmentService.class);
        RagArtifactChatHandlerService handlerService = new RagArtifactChatHandlerService(
                scopeResolverService,
                artifactRepository,
                mock(DocumentChunkRepository.class),
                documentEnrichmentService
        );
        DocumentNode node = node();

        when(scopeResolverService.resolve(any(), eq("Tạo câu hỏi Chương 1"))).thenReturn(Optional.of(node));
        when(artifactRepository.findLatestByNodeTypeAndStatus(100L, DocumentNodeArtifactType.REVIEW_QUESTION_SET, DocumentNodeArtifactStatus.COMPLETED))
                .thenReturn(List.of());
        when(documentEnrichmentService.prepareNodeArtifactGeneration(100L, DocumentNodeArtifactType.REVIEW_QUESTION_SET))
                .thenReturn(DocumentEnrichmentService.OnDemandArtifactStatus.QUEUED);

        RagArtifactChatHandlerService.ArtifactChatResult result =
                handlerService.handle(new ChatSession(), "Tạo câu hỏi Chương 1", RagChatIntent.REVIEW_QUESTION_GENERATION);

        assertThat(result.artifactHit()).isFalse();
        assertThat(result.answer()).contains("đang được tạo");
        assertThat(result.confidenceLevel()).isEqualTo("LOW");
        verify(documentEnrichmentService).enqueueNodeEnrichment(
                100L,
                false,
                List.of(DocumentNodeArtifactType.REVIEW_QUESTION_SET)
        );
    }

    private DocumentNode node() {
        Document document = Document.builder()
                .title("Giáo trình")
                .build();
        document.setId(10L);
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

    private DocumentNode partNode() {
        Document document = Document.builder()
                .title("Giáo trình")
                .build();
        document.setId(10L);
        DocumentNode node = DocumentNode.builder()
                .document(document)
                .nodeType("part")
                .title("Phần I")
                .sectionPath("Phần I")
                .orderIndex(1)
                .build();
        node.setId(300L);
        return node;
    }

    private DocumentChunk chunk() {
        Document document = Document.builder()
                .title("Giáo trình")
                .build();
        document.setId(10L);
        DocumentChunk chunk = DocumentChunk.builder()
                .document(document)
                .chunkType("TEXT")
                .sectionPath("Chương 1")
                .pageFrom(3)
                .pageTo(4)
                .content("Nguồn citation")
                .build();
        chunk.setId(200L);
        return chunk;
    }
}
