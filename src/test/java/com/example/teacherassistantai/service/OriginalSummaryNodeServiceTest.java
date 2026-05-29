package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OriginalSummaryNodeServiceTest {

    private final DocumentNodeRepository nodeRepository = mock(DocumentNodeRepository.class);
    private final DocumentChunkRepository chunkRepository = mock(DocumentChunkRepository.class);
    private final OriginalSummaryNodeService service = new OriginalSummaryNodeService(
            nodeRepository,
            chunkRepository,
            new OriginalSummaryTextCleaner()
    );

    @Test
    void findForChapter_returnsCleanedSummaryFromDirectSummaryNodeChunks() {
        Document document = document();
        DocumentNode chapter = node(document, 10L, "chapter", "Chương 1: Lý Luận Chung Về Nhà Nước",
                "Chương 1: Lý Luận Chung Về Nhà Nước");
        DocumentNode summary = node(document, 11L, "summary", "TÓM TẮT CHƯƠNG 1",
                "Chương 1: Lý Luận Chung Về Nhà Nước > TÓM TẮT CHƯƠNG 1");
        String overlap = "Nhà nước ban hành pháp luật và thực hiện quản lý bắt buộc với công dân.";
        DocumentChunk first = chunk(document, summary, 101L, """
                Chương 1: Lý Luận Chung Về Nhà Nước > TÓM TẮT CHƯƠNG 1

                Nhà nước là một tổ chức đặc biệt của quyền lực chính trị.

                %s
                """.formatted(overlap));
        DocumentChunk second = chunk(document, summary, 102L, """
                Chương 1: Lý Luận Chung Về Nhà Nước > TÓM TẮT CHƯƠNG 1

                %s

                Nhà nước có chủ quyền quốc gia.
                """.formatted(overlap));

        when(nodeRepository.findByParentIdAndNodeTypeOrderByOrderIndexAsc(10L, "summary"))
                .thenReturn(List.of(summary));
        when(chunkRepository.findByDocumentIdAndNodeIdOrderBySourceOrderAsc(1L, 11L))
                .thenReturn(List.of(first, second));

        Optional<OriginalSummaryNodeService.OriginalSummary> result = service.findForChapter(chapter);

        assertThat(result).isPresent();
        assertThat(result.get().content())
                .doesNotContain("Chương 1: Lý Luận Chung Về Nhà Nước > TÓM TẮT CHƯƠNG 1")
                .contains("Nhà nước là một tổ chức đặc biệt")
                .contains("Nhà nước có chủ quyền quốc gia.");
        assertThat(count(result.get().content(), overlap)).isEqualTo(1);
        assertThat(result.get().sources()).containsExactly(first, second);
        assertThat(result.get().cleanedChunks()).hasSize(2);
        assertThat(result.get().cleanedChunks().getFirst().getId()).isEqualTo(101L);
    }

    @Test
    void findForChapter_cleansPersistedSummaryNodeContentWhenAvailable() {
        Document document = document();
        DocumentNode chapter = node(document, 10L, "chapter", "Chương 1", "Chương 1");
        DocumentNode summary = node(document, 11L, "summary", "TÓM TẮT CHƯƠNG 1",
                "Chương 1 > TÓM TẮT CHƯƠNG 1");
        summary.setContent("""
                TÓM TẮT CHƯƠNG 1

                Nội dung tóm tắt đã lưu.
                """);

        when(nodeRepository.findByParentIdAndNodeTypeOrderByOrderIndexAsc(10L, "summary"))
                .thenReturn(List.of(summary));
        when(chunkRepository.findByDocumentIdAndNodeIdOrderBySourceOrderAsc(1L, 11L))
                .thenReturn(List.of());

        Optional<OriginalSummaryNodeService.OriginalSummary> result = service.findForChapter(chapter);

        assertThat(result).isPresent();
        assertThat(result.get().content()).isEqualTo("Nội dung tóm tắt đã lưu.");
    }

    private Document document() {
        Document document = Document.builder()
                .title("Giáo trình")
                .build();
        document.setId(1L);
        return document;
    }

    private DocumentNode node(Document document, Long id, String nodeType, String title, String sectionPath) {
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

    private DocumentChunk chunk(Document document, DocumentNode node, Long id, String content) {
        DocumentChunk chunk = DocumentChunk.builder()
                .document(document)
                .node(node)
                .chunkIndex(id.intValue())
                .sourceOrder(id.intValue())
                .chunkType("SUMMARY")
                .sectionPath(node.getSectionPath())
                .content(content)
                .build();
        chunk.setId(id);
        return chunk;
    }

    private int count(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
