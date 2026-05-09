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
        Path partyHistoryPath = outputDir.resolve("tika-gt-lich-su-dang-csvn-ban-tuyen-giao-tw.md");
        Path philosophyPath = outputDir.resolve("tika-75770b9b-cdbf-4038-90e2-f25e1f4426fe_triethocmaclenin.md");
        Path lawPath = outputDir.resolve("tika-Bài giảng Pháp luật đại cương Th.S Lê Thị Bích Ngọc.md");
        Assumptions.assumeTrue(
                Files.isRegularFile(partyHistoryPath) && Files.isRegularFile(philosophyPath) && Files.isRegularFile(lawPath),
                "sample markdown output files are not available"
        );

        List<HierarchicalMarkdownChunk> partyHistory = chunkingService.chunkHierarchical(
                Files.readString(partyHistoryPath));
        assertThat(partyHistory).isNotEmpty();
        assertThat(partyHistory)
                .anySatisfy(chunk -> assertThat(chunk.breadcrumb())
                        .contains("Chương 1: Đảng Cộng Sản Việt Nam Ra Đời Và Lãnh Đạo"));

        List<HierarchicalMarkdownChunk> philosophy = chunkingService.chunkHierarchical(
                Files.readString(philosophyPath));
        assertThat(philosophy).isNotEmpty();
        assertThat(philosophy)
                .anySatisfy(chunk -> assertThat(chunk.breadcrumb())
                        .contains("Phần I Khái lược về triết học và lịch sử triết học"));
        assertThat(philosophy)
                .noneSatisfy(chunk -> assertThat(chunk.sectionHeader())
                        .contains("Từ đầu thế kỷ XX, nhất là từ sau Chiến tranh thế giới thứ hai"));

        List<HierarchicalMarkdownChunk> law = chunkingService.chunkHierarchical(
                Files.readString(lawPath));
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

    @Test
    void chunkHierarchical_keeps_alpha_markersInsideParentSection() {
        MarkdownChunkingService.HierarchicalMarkdownDocument document =
                chunkingService.parseHierarchicalDocument("""
                        # Tài liệu

                        ### Chương 1: Tổng quan

                        #### I. Khái niệm

                        Nội dung mở đầu.

                        ##### a) Định nghĩa

                        Nội dung định nghĩa.

                        ##### b) Đặc điểm

                        Nội dung đặc điểm.
                        """);

        assertThat(document.root().children()).hasSize(1);
        MarkdownChunkingService.PublicHierarchyNode chapter = document.root().children().getFirst();
        assertThat(chapter.children()).hasSize(1);
        assertThat(chapter.children().getFirst().nodeType()).isEqualTo("section");
        assertThat(chapter.children().getFirst().children()).isEmpty();

        assertThat(document.chunks()).hasSize(1);
        assertThat(document.chunks().getFirst().nodeType()).isEqualTo("section");
        assertThat(document.chunks().getFirst().sectionHeader()).isEqualTo("I. Khái niệm");
        assertThat(document.chunks().getFirst().content())
                .contains("a) Định nghĩa")
                .contains("b) Đặc điểm")
                .doesNotContain("##### a)")
                .doesNotContain("##### b)");
    }

    @Test
    void parseHierarchicalDocument_demotes_singleNumberListAfterLeadin() {
        MarkdownChunkingService.HierarchicalMarkdownDocument document =
                chunkingService.parseHierarchicalDocument("""
                        # Tài liệu

                        ### Chương 4: Luật Hiến Pháp

                        #### 4.2. Những Chế Định Cơ Bản

                        ##### 4.2.1. Chế độ chính trị

                        Nội dung cơ bản của chế định chế độ chính trị bao gồm:

                        ##### 1. Khẳng định bản chất của Nhà nước ta là Nhà nước của nhân dân

                        nhân dân. Tất cả quyền lực nhà nước thuộc về nhân dân.

                        ##### 2. Xác định mục đích của nhà nước

                        Nhà nước bảo đảm quyền làm chủ của nhân dân.
                        """);

        MarkdownChunkingService.PublicHierarchyNode chapter = document.root().children().getFirst();
        MarkdownChunkingService.PublicHierarchyNode section = chapter.children().getFirst();
        MarkdownChunkingService.PublicHierarchyNode subsection = section.children().getFirst();

        assertThat(subsection.title()).isEqualTo("4.2.1. Chế độ chính trị");
        assertThat(subsection.children()).isEmpty();
        assertThat(document.normalizedMarkdown())
                .contains("1. Khẳng định bản chất của Nhà nước ta là Nhà nước của nhân dân")
                .doesNotContain("##### 1. Khẳng định bản chất");
        assertThat(document.chunks())
                .anySatisfy(chunk -> assertThat(chunk.content())
                        .contains("1. Khẳng định bản chất")
                        .contains("2. Xác định mục đích"));
    }

    @Test
    void parseHierarchicalDocument_distinguishes_singleNumberHeadingFromListItem() {
        MarkdownChunkingService.HierarchicalMarkdownDocument document =
                chunkingService.parseHierarchicalDocument("""
                        # Tài liệu

                        ### Chương 1: Tổng quan

                        #### I. Nhóm heading thật

                        ##### 1. Tiêu chí độc lập thứ nhất

                        Nội dung riêng của tiêu chí thứ nhất.

                        ##### 2. Tiêu chí độc lập thứ hai

                        Nội dung riêng của tiêu chí thứ hai.

                        #### II. Nhóm danh sách

                        Các nội dung cơ bản bao gồm:

                        ##### 1. Đây là list item thứ nhất

                        Nội dung list item thứ nhất.

                        ##### 2. Đây là list item thứ hai

                        Nội dung list item thứ hai.
                        """);

        MarkdownChunkingService.PublicHierarchyNode chapter = document.root().children().getFirst();
        MarkdownChunkingService.PublicHierarchyNode headingSection = chapter.children().get(0);
        MarkdownChunkingService.PublicHierarchyNode listSection = chapter.children().get(1);

        assertThat(headingSection.children())
                .extracting(MarkdownChunkingService.PublicHierarchyNode::title)
                .containsExactly("1. Tiêu chí độc lập thứ nhất", "2. Tiêu chí độc lập thứ hai");
        assertThat(headingSection.children())
                .extracting(MarkdownChunkingService.PublicHierarchyNode::nodeType)
                .containsExactly("subsection", "subsection");

        assertThat(listSection.children()).isEmpty();
        assertThat(document.chunks())
                .filteredOn(chunk -> listSection.nodeId().equals(chunk.nodeId()))
                .anySatisfy(chunk -> assertThat(chunk.content())
                        .contains("1. Đây là list item thứ nhất")
                        .contains("2. Đây là list item thứ hai")
                        .doesNotContain("##### 1. Đây là list item")
                        .doesNotContain("##### 2. Đây là list item"));
    }

    @Test
    void parseHierarchicalDocument_supports_subsectionLevel2OnlyForFourPartDecimalHeadings() {
        MarkdownChunkingService.HierarchicalMarkdownDocument document =
                chunkingService.parseHierarchicalDocument("""
                        # Tài liệu

                        ### Chương 1: Tổng quan

                        #### 1.1. Mục lớn

                        ##### 1.1.1. Mục con

                        ##### 1.1.1.1. Mục con cấp hai

                        Nội dung cấp hai.

                        ##### 1.1.1.1.1. Không tạo node riêng

                        Nội dung vẫn thuộc cấp hai.
                        """);

        MarkdownChunkingService.PublicHierarchyNode chapter = document.root().children().getFirst();
        MarkdownChunkingService.PublicHierarchyNode section = chapter.children().getFirst();
        MarkdownChunkingService.PublicHierarchyNode subsection = section.children().getFirst();
        MarkdownChunkingService.PublicHierarchyNode subsectionLevel2 = subsection.children().getFirst();

        assertThat(subsection.nodeType()).isEqualTo("subsection");
        assertThat(subsectionLevel2.nodeType()).isEqualTo("subsection_level2");
        assertThat(subsectionLevel2.children()).isEmpty();
        assertThat(document.normalizedMarkdown())
                .contains("1.1.1.1.1. Không tạo node riêng")
                .doesNotContain("##### 1.1.1.1.1.");
        assertThat(document.chunks())
                .anySatisfy(chunk -> {
                    assertThat(chunk.nodeType()).isEqualTo("subsection_level2");
                    assertThat(chunk.content()).contains("Không tạo node riêng");
                });
    }

    @Test
    void parseHierarchicalDocument_demotes_invalidPartHeadingFromBodyText() {
        MarkdownChunkingService.HierarchicalMarkdownDocument document =
                chunkingService.parseHierarchicalDocument("""
                        # Tài liệu

                        ### Chương 6: Luật Dân Sự

                        #### 6.1. Luật Dân Sự

                        ## phần di sản bằng 2/3 xuất của 1 người thừa kế theo pháp luật nếu di sản được chia theo

                        pháp luật trong trường hợp họ không được người lập di chúc cho hưởng di sản.
                        """);

        assertThat(document.root().children()).hasSize(1);
        assertThat(document.root().children().getFirst().nodeType()).isEqualTo("chapter");
        assertThat(document.normalizedMarkdown())
                .contains("phần di sản bằng 2/3")
                .doesNotContain("## phần di sản");
        assertThat(document.chunks())
                .noneSatisfy(chunk -> assertThat(chunk.breadcrumb()).contains("phần di sản bằng 2/3"));
    }

    @Test
    void parseHierarchicalDocument_usesProvidedDocumentTitleForRoot() {
        MarkdownChunkingService.HierarchicalMarkdownDocument document =
                chunkingService.parseHierarchicalDocument("""
                        # Slug title

                        ### Chương 1: Tổng quan

                        Nội dung.
                        """, "Tên tài liệu thật");

        assertThat(document.root().nodeType()).isEqualTo("document");
        assertThat(document.root().title()).isEqualTo("Tên tài liệu thật");
    }

    @Test
    void parseHierarchicalDocument_allowsSingleNumberHeadingsUnderConclusion() {
        MarkdownChunkingService.HierarchicalMarkdownDocument document =
                chunkingService.parseHierarchicalDocument("""
                        # Tài liệu

                        ### Chương 3: Tổng kết

                        KẾT LUẬN

                        Những bài học lớn.

                        ##### 1. Bài học thứ nhất

                        Nội dung bài học thứ nhất.

                        ##### 2. Bài học thứ hai

                        Nội dung bài học thứ hai.
                        """);

        MarkdownChunkingService.PublicHierarchyNode chapter = document.root().children().getFirst();
        MarkdownChunkingService.PublicHierarchyNode conclusion = chapter.children().getFirst();

        assertThat(conclusion.nodeType()).isEqualTo("section");
        assertThat(conclusion.title()).isEqualTo("KẾT LUẬN");
        assertThat(conclusion.children()).hasSize(2);
        assertThat(conclusion.children())
                .extracting(MarkdownChunkingService.PublicHierarchyNode::nodeType)
                .containsExactly("subsection", "subsection");
        assertThat(document.chunks())
                .filteredOn(chunk -> chunk.nodeType().equals("subsection"))
                .allSatisfy(chunk -> assertThat(chunk.content()).doesNotContain("#####"));
    }

    @Test
    void parseHierarchicalDocument_stripsMarkdownMarkersInsideSpecialChunks() {
        MarkdownChunkingService.HierarchicalMarkdownDocument document =
                chunkingService.parseHierarchicalDocument("""
                        # Tài liệu

                        ### Chương 1: Tổng quan

                        ### NỘI DUNG ÔN TẬP VÀ THẢO LUẬN

                        ##### 1. Câu hỏi thứ nhất?

                        ##### 2. Câu hỏi thứ hai?
                        """);

        assertThat(document.chunks())
                .filteredOn(chunk -> chunk.chunkType().equals("REVIEW_QUESTIONS"))
                .anySatisfy(chunk -> assertThat(chunk.content())
                        .contains("1. Câu hỏi thứ nhất?")
                        .contains("2. Câu hỏi thứ hai?")
                        .doesNotContain("#####"));
    }

    @Test
    void parseHierarchicalDocument_splitsCitationBlocksFromTextChunks() {
        MarkdownChunkingService.HierarchicalMarkdownDocument document =
                chunkingService.parseHierarchicalDocument("""
                        # Tài liệu

                        ### Chương 1: Tổng quan

                        #### I. Khái niệm

                        “Lý luận do kinh nghiệm cách mạng ... thành ra lý luận”4.

                        3 Hồ Chí Minh Toàn tập, Nxb Chính trị quốc gia, Hà Nội, 2011, tập 5, trang 273.
                        4 Hồ Chí Minh Toàn tập, Nxb Chính trị quốc gia, Hà Nội, 2011, tập 5, trang 312.
                        5 Đảng Cộng sản Việt Nam: Văn kiện Đảng Toàn tập, Nxb Chính trị quốc gia, Hà Nội, 2015, tập 55, trang 356.

                        Nội dung sau footnote vẫn là text.
                        """);

        assertThat(document.normalizedMarkdown())
                .contains("3 Hồ Chí Minh Toàn tập")
                .contains("Nội dung sau footnote vẫn là text.");
        assertThat(document.chunks())
                .anySatisfy(chunk -> {
                    assertThat(chunk.chunkType()).isEqualTo("TEXT");
                    assertThat(chunk.content())
                            .contains("“Lý luận do kinh nghiệm cách mạng")
                            .doesNotContain("3 Hồ Chí Minh Toàn tập")
                            .doesNotContain("5 Đảng Cộng sản Việt Nam");
                })
                .anySatisfy(chunk -> {
                    assertThat(chunk.chunkType()).isEqualTo("CITATION");
                    assertThat(chunk.content())
                            .contains("3 Hồ Chí Minh Toàn tập")
                            .contains("5 Đảng Cộng sản Việt Nam");
                });
    }
}
