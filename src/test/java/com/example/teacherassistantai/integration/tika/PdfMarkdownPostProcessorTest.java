package com.example.teacherassistantai.integration.tika;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PdfMarkdownPostProcessorTest {

    private final PdfMarkdownPostProcessor postProcessor = new PdfMarkdownPostProcessor();

    @Test
    void toMarkdown_doesNotPromoteAlphaMarkersToHeadings() {
        String markdown = postProcessor.toMarkdown("""
                Chương 1
                Tổng quan
                I. Khái niệm
                a) Định nghĩa
                Nội dung định nghĩa.
                b) Đặc điểm
                Nội dung đặc điểm.
                """, "Tài liệu");

        assertThat(markdown).contains("### Chương 1 Tổng quan");
        assertThat(markdown).contains("#### I. Khái niệm");
        assertThat(markdown).contains("a) Định nghĩa");
        assertThat(markdown).contains("b) Đặc điểm");
        assertThat(markdown).doesNotContain("###### a) Định nghĩa");
        assertThat(markdown).doesNotContain("###### b) Đặc điểm");
    }
}
