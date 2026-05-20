package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceAttributionFormatterTest {

    private final SourceAttributionFormatter formatter = new SourceAttributionFormatter();

    @Test
    void format_includesDocumentTitleAndSinglePage() {
        assertThat(formatter.format(chunk("Giáo trình", null, 12, 12)))
                .isEqualTo("Giáo trình (trang 12)");
    }

    @Test
    void format_includesDocumentTitleAndPageRange() {
        assertThat(formatter.format(chunk("Giáo trình", null, 12, 13)))
                .isEqualTo("Giáo trình (trang 12-13)");
    }

    @Test
    void format_omitsPageWhenMissing() {
        assertThat(formatter.format(chunk("Giáo trình", null, null, null)))
                .isEqualTo("Giáo trình");
    }

    @Test
    void format_omitsSectionPath() {
        assertThat(formatter.format(chunk("Giáo trình", "Chương 1 > I. Khái niệm", 12, 13)))
                .isEqualTo("Giáo trình (trang 12-13)");
    }

    @Test
    void formatSources_dedupesSameDocumentAndPage() {
        DocumentChunk first = chunk("Giáo trình", "Chương 1", 3, 4);
        DocumentChunk duplicate = chunk("Giáo trình", "Chương 2", 3, 4);
        duplicate.setId(99L);

        assertThat(formatter.formatSources(List.of(first, duplicate)))
                .containsExactly("Giáo trình (trang 3-4)");
    }

    @Test
    void formatSources_limitsToThreeLabels() {
        assertThat(formatter.formatSources(List.of(
                chunk("Giáo trình", "Chương 1", 1, 2),
                chunk("Giáo trình", "Chương 2", 3, 4),
                chunk("Giáo trình", "Chương 3", 5, 6),
                chunk("Giáo trình", "Chương 4", 7, 8)
        ))).containsExactly(
                "Giáo trình (trang 1-2)",
                "Giáo trình (trang 3-4)",
                "Giáo trình (trang 5-6)"
        );
    }

    private DocumentChunk chunk(String title, String sectionPath, Integer pageFrom, Integer pageTo) {
        Document document = Document.builder()
                .title(title)
                .build();
        document.setId(10L);
        DocumentChunk chunk = DocumentChunk.builder()
                .document(document)
                .sectionPath(sectionPath)
                .pageFrom(pageFrom)
                .pageTo(pageTo)
                .content("source")
                .build();
        chunk.setId(20L);
        return chunk;
    }
}
