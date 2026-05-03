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
        assertThat(law)
                .filteredOn(chunk -> chunk.chunkType().equals("SUMMARY"))
                .anySatisfy(chunk -> assertThat(chunk.content()).contains("Nhà nước là một tổ chức đặc biệt"));
        assertThat(law)
                .filteredOn(chunk -> chunk.chunkType().equals("REVIEW_QUESTIONS"))
                .anySatisfy(chunk -> assertThat(chunk.content()).contains("Tại sao nói nhà nước ra đời"));
    }

    @Test
    void normalizeMarkdownForHierarchy_repairs_broken_and_attached_headings() {
        String markdown = """
                ### Chương 5: Luật Hành Chính Hoạt động chấp hành và điều hành là một loại hoạt động quản lý.

                #### III. Phương pháp nghiên cứu, học tập môn học Lịch sử Đảng Cộng sản Việt

                Nam
                """;

        String normalized = chunkingService.normalizeMarkdownForHierarchy(markdown);

        assertThat(normalized).contains("""
                ### Chương 5: Luật Hành Chính

                Hoạt động chấp hành và điều hành là một loại hoạt động quản lý.""");
        assertThat(normalized).contains("#### III. Phương pháp nghiên cứu, học tập môn học Lịch sử Đảng Cộng sản Việt Nam");
    }

    @Test
    void chunkHierarchical_treats_numbered_question_headings_asReviewQuestions() {
        List<HierarchicalMarkdownChunk> chunks = chunkingService.chunkHierarchical("""
                # Triết

                ### Chương X

                ##### 1. Quan điểm của triết học duy vật biện chứng về nguồn gốc và bản chất của ý thức?

                Câu hỏi ôn tập.
                """);

        assertThat(chunks)
                .anySatisfy(chunk -> {
                    assertThat(chunk.chunkType()).isEqualTo("REVIEW_QUESTIONS");
                    assertThat(chunk.sectionHeader()).contains("Quan điểm của triết học");
                });
    }
}
