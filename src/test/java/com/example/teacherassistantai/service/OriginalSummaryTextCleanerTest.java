package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OriginalSummaryTextCleanerTest {

    private final OriginalSummaryTextCleaner cleaner = new OriginalSummaryTextCleaner();

    @Test
    void clean_removesRepeatedBreadcrumbHeadingAndAdjacentOverlap() {
        DocumentNode summaryNode = summaryNode();
        String overlap = "Nhà nước ban hành pháp luật và thực hiện quản lý bắt buộc với công dân.";
        DocumentChunk first = chunk(101L, """
                Chương 1: Lý Luận Chung Về Nhà Nước > TÓM TẮT CHƯƠNG 1

                TÓM TẮT CHƯƠNG 1

                Nhà nước là một tổ chức đặc biệt của quyền lực chính trị.

                %s
                """.formatted(overlap));
        DocumentChunk second = chunk(102L, """
                Chương 1: Lý Luận Chung Về Nhà Nước > TÓM TẮT CHƯƠNG 1

                %s

                Nhà nước quy định các loại thuế và thực hiện thu thuế bắt buộc.
                """.formatted(overlap));

        OriginalSummaryTextCleaner.CleanedOriginalSummary result =
                cleaner.clean(summaryNode, List.of(first, second));

        assertThat(result.cleanedText())
                .doesNotContain("Chương 1: Lý Luận Chung Về Nhà Nước > TÓM TẮT CHƯƠNG 1")
                .doesNotContain("TÓM TẮT CHƯƠNG 1")
                .contains("Nhà nước là một tổ chức đặc biệt")
                .contains("Nhà nước quy định các loại thuế");
        assertThat(count(result.cleanedText(), overlap)).isEqualTo(1);
        assertThat(result.cleanedChunks()).hasSize(2);
        assertThat(result.cleanedChunks().getFirst().getId()).isEqualTo(101L);
        assertThat(result.cleanedChunks().get(1).getId()).isEqualTo(102L);
        assertThat(result.stats().removedBreadcrumbCount()).isEqualTo(2);
        assertThat(result.stats().removedHeadingCount()).isEqualTo(1);
        assertThat(result.stats().removedOverlapCount()).isEqualTo(1);
    }

    @Test
    void cleanText_preservesVietnameseContentWhenNoNoiseExists() {
        DocumentNode summaryNode = summaryNode();

        OriginalSummaryTextCleaner.CleanedText result = cleaner.cleanText(
                summaryNode,
                "Nhà nước có chủ quyền quốc gia.\n\nNhà nước ban hành pháp luật."
        );

        assertThat(result.text()).isEqualTo("Nhà nước có chủ quyền quốc gia.\n\nNhà nước ban hành pháp luật.");
        assertThat(result.stats().removedBreadcrumbCount()).isZero();
        assertThat(result.stats().removedHeadingCount()).isZero();
    }

    private DocumentNode summaryNode() {
        DocumentNode node = DocumentNode.builder()
                .nodeType("summary")
                .title("TÓM TẮT CHƯƠNG 1")
                .sectionPath("Chương 1: Lý Luận Chung Về Nhà Nước > TÓM TẮT CHƯƠNG 1")
                .build();
        node.setId(11L);
        return node;
    }

    private DocumentChunk chunk(Long id, String content) {
        DocumentChunk chunk = DocumentChunk.builder()
                .chunkIndex(id.intValue())
                .sourceOrder(id.intValue())
                .chunkType("SUMMARY")
                .sectionPath("Chương 1: Lý Luận Chung Về Nhà Nước > TÓM TẮT CHƯƠNG 1")
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
