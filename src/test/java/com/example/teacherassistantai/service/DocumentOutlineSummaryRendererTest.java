package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentOutlineSummaryRendererTest {

    @Test
    void render_documentUsesOutlineWordingAndNestedStructure() {
        DocumentNodeRepository nodeRepository = mock(DocumentNodeRepository.class);
        DocumentOutlineSummaryRenderer renderer = new DocumentOutlineSummaryRenderer(nodeRepository);
        DocumentNode documentRoot = node(1L, "document", "Giáo trình", "Giáo trình");
        DocumentNode part = node(2L, "part", "Phần I", "Phần I");
        DocumentNode nestedChapter = node(3L, "chapter", "Chương 1", "Phần I > Chương 1");
        DocumentNode directChapter = node(4L, "chapter", "Chương 2", "Chương 2");

        when(nodeRepository.findByParentIdOrderByOrderIndexAsc(1L)).thenReturn(List.of(part, directChapter));
        when(nodeRepository.findByParentIdOrderByOrderIndexAsc(2L)).thenReturn(List.of(nestedChapter));

        Optional<String> result = renderer.render(documentRoot);

        assertThat(result).isPresent();
        assertThat(result.get()).startsWith("Cấu trúc Giáo trình:");
        assertThat(result.get()).contains("Tài liệu gồm các phần/chương chính sau:");
        assertThat(result.get()).contains("- Phần I");
        assertThat(result.get()).contains("  - Chương 1");
        assertThat(result.get()).contains("- Chương 2");
        assertThat(result.get()).doesNotContain("Tóm tắt tổng quan");
    }

    @Test
    void render_partUsesOutlineWording() {
        DocumentNodeRepository nodeRepository = mock(DocumentNodeRepository.class);
        DocumentOutlineSummaryRenderer renderer = new DocumentOutlineSummaryRenderer(nodeRepository);
        DocumentNode part = node(2L, "part", "Phần I", "Phần I");
        DocumentNode chapter = node(3L, "chapter", "Chương 1", "Phần I > Chương 1");

        when(nodeRepository.findByParentIdOrderByOrderIndexAsc(2L)).thenReturn(List.of(chapter));

        Optional<String> result = renderer.render(part);

        assertThat(result).isPresent();
        assertThat(result.get()).startsWith("Cấu trúc Phần I:");
        assertThat(result.get()).contains("Phần này gồm các chương/nội dung chính sau:");
        assertThat(result.get()).contains("- Chương 1");
        assertThat(result.get()).doesNotContain("Tóm tắt tổng quan");
    }

    @Test
    void render_unsupportedNodeTypeReturnsEmpty() {
        DocumentNodeRepository nodeRepository = mock(DocumentNodeRepository.class);
        DocumentOutlineSummaryRenderer renderer = new DocumentOutlineSummaryRenderer(nodeRepository);

        Optional<String> result = renderer.render(node(3L, "chapter", "Chương 1", "Chương 1"));

        assertThat(result).isEmpty();
    }

    private DocumentNode node(Long id, String nodeType, String title, String sectionPath) {
        Document document = Document.builder()
                .title("Giáo trình")
                .build();
        document.setId(10L);
        DocumentNode node = DocumentNode.builder()
                .document(document)
                .nodeType(nodeType)
                .title(title)
                .sectionPath(sectionPath)
                .orderIndex(id.intValue())
                .build();
        node.setId(id);
        return node;
    }
}
