package com.example.teacherassistantai.service.agent;

import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.service.DocumentOutlineSummaryRenderer;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutlineAgentTest {

    @Test
    void execute_documentReturnsOutlineWithoutSourcesOrArtifactHit() {
        DocumentOutlineSummaryRenderer renderer = mock(DocumentOutlineSummaryRenderer.class);
        OutlineAgent agent = new OutlineAgent(renderer);
        DocumentNode document = node(1L, "document", "Giáo trình");
        when(renderer.render(document)).thenReturn(Optional.of("Cấu trúc Giáo trình"));

        AgentResult result = agent.execute(state(document));

        assertThat(result.answer()).isEqualTo("Cấu trúc Giáo trình");
        assertThat(result.sources()).isEmpty();
        assertThat(result.confidenceLevel()).isEqualTo("HIGH");
        assertThat(result.artifactHit()).isFalse();
        assertThat(result.ragFallback()).isFalse();
    }

    @Test
    void execute_partReturnsOutlineWithoutSources() {
        DocumentOutlineSummaryRenderer renderer = mock(DocumentOutlineSummaryRenderer.class);
        OutlineAgent agent = new OutlineAgent(renderer);
        DocumentNode part = node(2L, "part", "Phần I");
        when(renderer.render(part)).thenReturn(Optional.of("Cấu trúc Phần I"));

        AgentResult result = agent.execute(state(part));

        assertThat(result.answer()).isEqualTo("Cấu trúc Phần I");
        assertThat(result.sources()).isEmpty();
        assertThat(result.artifactHit()).isFalse();
    }

    @Test
    void execute_unsupportedNodeReturnsMessageAndDoesNotRender() {
        DocumentOutlineSummaryRenderer renderer = mock(DocumentOutlineSummaryRenderer.class);
        OutlineAgent agent = new OutlineAgent(renderer);
        DocumentNode chapter = node(3L, "chapter", "Chương 1");

        AgentResult result = agent.execute(state(chapter));

        assertThat(result.answer()).contains("chỉ hỗ trợ xem cấu trúc ở cấp tài liệu hoặc phần");
        assertThat(result.sources()).isEmpty();
        assertThat(result.confidenceLevel()).isEqualTo("LOW");
        verify(renderer, never()).render(chapter);
    }

    private RagChatState state(DocumentNode node) {
        return RagChatState.builder()
                .resolvedNode(node)
                .build();
    }

    private DocumentNode node(Long id, String nodeType, String title) {
        Document document = Document.builder()
                .title("Giáo trình")
                .build();
        document.setId(10L);
        DocumentNode node = DocumentNode.builder()
                .document(document)
                .nodeType(nodeType)
                .title(title)
                .sectionPath(title)
                .orderIndex(id.intValue())
                .build();
        node.setId(id);
        return node;
    }
}
