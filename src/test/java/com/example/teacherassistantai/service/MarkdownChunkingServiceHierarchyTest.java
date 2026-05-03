package com.example.teacherassistantai.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownChunkingServiceHierarchyTest {

    private final MarkdownChunkingService chunkingService = new MarkdownChunkingService();

    @Test
    void chunkHierarchical_keeps_academic_breadcrumbs_for_sample_outputs() throws Exception {
        Path outputDir = Path.of("output");
        Assumptions.assumeTrue(Files.isDirectory(outputDir), "sample output directory is not available");

        List<HierarchicalMarkdownChunk> partyHistory = chunkingService.chunkHierarchical(
                Files.readString(outputDir.resolve("tika-gt-lich-su-dang-csvn-ban-tuyen-giao-tw.md")));
        assertThat(partyHistory).isNotEmpty();
        assertThat(partyHistory)
                .anySatisfy(chunk -> assertThat(chunk.breadcrumb())
                        .contains("Chương 1: Đảng Cộng Sản Việt Nam Ra Đời Và Lãnh Đạo"));

        List<HierarchicalMarkdownChunk> philosophy = chunkingService.chunkHierarchical(
                Files.readString(outputDir.resolve("tika-75770b9b-cdbf-4038-90e2-f25e1f4426fe_triethocmaclenin.md")));
        assertThat(philosophy).isNotEmpty();
        assertThat(philosophy)
                .anySatisfy(chunk -> assertThat(chunk.breadcrumb())
                        .contains("Phần I Khái lược về triết học và lịch sử triết học"));
        assertThat(philosophy)
                .noneSatisfy(chunk -> assertThat(chunk.sectionHeader())
                        .contains("Từ đầu thế kỷ XX, nhất là từ sau Chiến tranh thế giới thứ hai"));

        List<HierarchicalMarkdownChunk> law = chunkingService.chunkHierarchical(
                Files.readString(outputDir.resolve("tika-Bài giảng Pháp luật đại cương Th.S Lê Thị Bích Ngọc.md")));
        assertThat(law).isNotEmpty();
        assertThat(law)
                .anySatisfy(chunk -> assertThat(chunk.chunkType()).isEqualTo("SUMMARY"));
        assertThat(law)
                .anySatisfy(chunk -> assertThat(chunk.chunkType()).isEqualTo("REVIEW_QUESTIONS"));
    }
}
