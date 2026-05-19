package com.example.teacherassistantai.service.agent;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.integration.ai.AiChatGateway;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import com.example.teacherassistantai.service.DocumentEnrichmentService;
import com.example.teacherassistantai.service.DocumentNodeScopeService;
import com.example.teacherassistantai.service.DocumentOutlineSummaryRenderer;
import com.example.teacherassistantai.service.OriginalSummaryNodeService;
import com.example.teacherassistantai.service.RagArtifactChatHandlerService;
import com.example.teacherassistantai.service.VectorRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SummaryAgentTest {

    private DocumentNodeArtifactRepository artifactRepository;
    private RagArtifactChatHandlerService handlerService;
    private OriginalSummaryNodeService originalSummaryNodeService;
    private DocumentOutlineSummaryRenderer outlineSummaryRenderer;
    private DocumentEnrichmentService documentEnrichmentService;
    private RagProperties ragProperties;
    private SummaryAgent summaryAgent;

    @BeforeEach
    void setUp() {
        artifactRepository = mock(DocumentNodeArtifactRepository.class);
        handlerService = mock(RagArtifactChatHandlerService.class);
        originalSummaryNodeService = mock(OriginalSummaryNodeService.class);
        outlineSummaryRenderer = mock(DocumentOutlineSummaryRenderer.class);
        documentEnrichmentService = mock(DocumentEnrichmentService.class);
        ragProperties = new RagProperties();
        summaryAgent = new SummaryAgent(
                artifactRepository,
                mock(DocumentRepository.class),
                handlerService,
                mock(VectorRetrievalService.class),
                mock(DocumentNodeScopeService.class),
                mock(AiChatGateway.class),
                mock(RedisTemplate.class),
                ragProperties,
                mock(PlatformTransactionManager.class),
                originalSummaryNodeService,
                outlineSummaryRenderer,
                documentEnrichmentService
        );
    }

    @Test
    void execute_chapterPrefersCompletedArtifactBeforeOriginalSummaryNode() {
        DocumentNode chapter = node(10L, "chapter", "Chương I", "Chương I");
        DocumentNode summaryNode = node(11L, "summary", "TÓM TẮT CHƯƠNG I", "TÓM TẮT CHƯƠNG I");
        DocumentChunk source = chunk(100L);
        DocumentNodeArtifact artifact = DocumentNodeArtifact.builder()
                .documentNode(chapter)
                .artifactType(DocumentNodeArtifactType.SUMMARY)
                .contentJsonb(Map.of("summary", "Tóm tắt artifact"))
                .build();
        when(outlineSummaryRenderer.render(chapter)).thenReturn(Optional.empty());
        when(artifactRepository.findLatestCompletedSummaryByNodeId(10L)).thenReturn(Optional.of(artifact));
        when(handlerService.renderSummary(artifact.getContentJsonb(), chapter)).thenReturn("Tóm tắt artifact");
        when(handlerService.sourceChunksFromCitations(artifact.getContentJsonb())).thenReturn(List.of(source));
        when(originalSummaryNodeService.findForChapter(chapter))
                .thenReturn(Optional.of(new OriginalSummaryNodeService.OriginalSummary(
                        summaryNode,
                        "Nội dung tóm tắt gốc của chương.",
                        List.of(source)
                )));

        AgentResult result = summaryAgent.execute(state(chapter));

        assertThat(result.answer()).isEqualTo("Tóm tắt artifact");
        assertThat(result.sources()).containsExactly(source);
        verify(originalSummaryNodeService, never()).findForChapter(chapter);
    }

    @Test
    void execute_chapterUsesOriginalSummaryWhenArtifactMissingAndContentIsShort() {
        DocumentNode chapter = node(10L, "chapter", "Chương I", "Chương I");
        DocumentNode summaryNode = node(11L, "summary", "TÓM TẮT CHƯƠNG I", "TÓM TẮT CHƯƠNG I");
        DocumentChunk source = chunk(100L);
        when(outlineSummaryRenderer.render(chapter)).thenReturn(Optional.empty());
        when(artifactRepository.findLatestCompletedSummaryByNodeId(10L)).thenReturn(Optional.empty());
        when(originalSummaryNodeService.findForChapter(chapter))
                .thenReturn(Optional.of(new OriginalSummaryNodeService.OriginalSummary(
                        summaryNode,
                        "Nội dung tóm tắt gốc của chương.",
                        List.of(source)
                )));

        AgentResult result = summaryAgent.execute(state(chapter));

        assertThat(result.answer()).contains("Nội dung tóm tắt gốc của chương.");
        assertThat(result.sources()).containsExactly(source);
    }

    @Test
    void execute_chapterQueuesNormalizationWhenOriginalSummaryIsTooLong() {
        DocumentNode chapter = node(10L, "chapter", "Chương I", "Chương I");
        DocumentNode summaryNode = node(11L, "summary", "TÓM TẮT CHƯƠNG I", "TÓM TẮT CHƯƠNG I");
        ragProperties.getEnrichment().setMaxDirectOriginalSummaryChars(20);
        when(outlineSummaryRenderer.render(chapter)).thenReturn(Optional.empty());
        when(artifactRepository.findLatestCompletedSummaryByNodeId(10L)).thenReturn(Optional.empty());
        when(originalSummaryNodeService.findForChapter(chapter))
                .thenReturn(Optional.of(new OriginalSummaryNodeService.OriginalSummary(
                        summaryNode,
                        "Nội dung tóm tắt gốc của chương rất dài.",
                        List.of(chunk(100L))
                )));

        AgentResult result = summaryAgent.execute(state(chapter));

        assertThat(result.answer()).contains("đang được chuẩn hóa");
        verify(documentEnrichmentService).enqueueNodeEnrichment(
                10L,
                false,
                List.of(DocumentNodeArtifactType.SUMMARY)
        );
    }

    @Test
    void execute_sectionDoesNotUseOriginalChapterSummary() {
        DocumentNode section = node(20L, "section", "1.1. Mục", "Chương I > 1.1. Mục");
        DocumentNodeArtifact artifact = DocumentNodeArtifact.builder()
                .documentNode(section)
                .artifactType(DocumentNodeArtifactType.SUMMARY)
                .contentJsonb(Map.of("summary", "Tóm tắt artifact"))
                .build();
        when(outlineSummaryRenderer.render(section)).thenReturn(Optional.empty());
        when(artifactRepository.findLatestCompletedSummaryByNodeId(20L)).thenReturn(Optional.of(artifact));
        when(handlerService.renderSummary(artifact.getContentJsonb(), section)).thenReturn("Tóm tắt artifact");
        when(handlerService.sourceChunksFromCitations(artifact.getContentJsonb())).thenReturn(List.of());

        AgentResult result = summaryAgent.execute(state(section));

        assertThat(result.answer()).isEqualTo("Tóm tắt artifact");
        verify(originalSummaryNodeService, never()).findForChapter(section);
    }

    @Test
    void execute_partReturnsOutlineSummary() {
        DocumentNode part = node(30L, "part", "Phần I", "Phần I");
        when(outlineSummaryRenderer.render(part)).thenReturn(Optional.of("Tóm tắt tổng quan Phần I"));

        AgentResult result = summaryAgent.execute(state(part));

        assertThat(result.answer()).contains("Tóm tắt tổng quan Phần I");
        verify(artifactRepository, never()).findLatestCompletedSummaryByNodeId(30L);
    }

    private RagChatState state(DocumentNode node) {
        return RagChatState.builder()
                .resolvedNode(node)
                .question("Tóm tắt")
                .build();
    }

    private DocumentNode node(Long id, String nodeType, String title, String sectionPath) {
        Document document = Document.builder()
                .title("Giáo trình")
                .build();
        document.setId(1L);
        DocumentNode node = DocumentNode.builder()
                .document(document)
                .nodeType(nodeType)
                .title(title)
                .sectionPath(sectionPath)
                .orderIndex(1)
                .build();
        node.setId(id);
        return node;
    }

    private DocumentChunk chunk(Long id) {
        DocumentChunk chunk = DocumentChunk.builder()
                .content("source")
                .chunkIndex(1)
                .build();
        chunk.setId(id);
        return chunk;
    }
}
