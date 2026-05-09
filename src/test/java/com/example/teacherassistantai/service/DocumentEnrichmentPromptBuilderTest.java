package com.example.teacherassistantai.service;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentEnrichmentPromptBuilderTest {

    private final DocumentEnrichmentPromptBuilder promptBuilder =
            new DocumentEnrichmentPromptBuilder(new RagProperties());

    @Test
    void buildSummaryPrompt_includesJsonPolicyAndScopeContext() {
        Fixture fixture = fixture();

        String prompt = promptBuilder.buildSummaryPrompt(fixture.document(), fixture.node(), List.of(fixture.chunk()));

        assertThat(prompt).contains("Tao summary");
        assertThat(prompt).contains("Chi dua vao context chunks");
        assertThat(prompt).contains("Chi tra ve mot JSON object hop le");
        assertThat(prompt).contains("\"summaryMode\": \"string\"");
        assertThat(prompt).contains("\"summary\": \"string\"");
        assertThat(prompt).contains("\"coverage\"");
        assertThat(prompt).contains("summaryMode bat buoc: CHAPTER_FALLBACK");
        assertThat(prompt).contains("chunkId: 200");
        assertThat(prompt).contains("path: Chương 1");
        assertThat(prompt).contains("pages: 3-4");
        assertThat(prompt).contains("Nội dung học tập quan trọng");
    }

    @Test
    void buildSectionSummaryPrompt_includesChildSummariesDirectChunksAndCoverage() {
        Fixture fixture = fixture();
        SummaryGenerationContext context = new SummaryGenerationContext(
                fixture.document(),
                fixture.node(),
                SummaryMode.SECTION_FROM_SUBSECTIONS_AND_DIRECT_CHUNKS,
                List.of(fixture.chunk()),
                List.of(new ChildSummary(
                        101L,
                        "subsection",
                        "1.1",
                        "Chương 1 > 1.1",
                        901L,
                        "hash-1",
                        "Tóm tắt subsection.",
                        List.of(Map.of("chunkId", 200L))
                )),
                new SummaryCoverage(1, 1, List.of(), 1, 1, true)
        );

        String prompt = promptBuilder.buildSectionSummaryPrompt(context);

        assertThat(prompt).contains("Tao summary node");
        assertThat(prompt).contains("summaryMode bat buoc: SECTION_FROM_SUBSECTIONS_AND_DIRECT_CHUNKS");
        assertThat(prompt).contains("expectedChildCount: 1");
        assertThat(prompt).contains("nodeId: 101");
        assertThat(prompt).contains("Tóm tắt subsection.");
        assertThat(prompt).contains("Nội dung học tập quan trọng");
    }

    @Test
    void buildPartSummaryPrompt_requiresOverviewAndChapterParagraphs() {
        Fixture fixture = fixture();
        SummaryGenerationContext context = new SummaryGenerationContext(
                fixture.document(),
                fixture.node(),
                SummaryMode.PART_FROM_CHAPTERS,
                List.of(),
                List.of(new ChildSummary(
                        102L,
                        "chapter",
                        "Chương 1",
                        "Phần I > Chương 1",
                        902L,
                        "hash-2",
                        "Tóm tắt chapter.",
                        List.of()
                )),
                new SummaryCoverage(1, 1, List.of(), 0, 0, true)
        );

        String prompt = promptBuilder.buildPartSummaryPrompt(context);

        assertThat(prompt).contains("Tao summary part");
        assertThat(prompt).contains("overview ngan");
        assertThat(prompt).contains("Moi chapter/childSummary chinh phai co mot doan noi dung ngan");
        assertThat(prompt).contains("summaryMode bat buoc: PART_FROM_CHAPTERS");
    }

    @Test
    void buildReviewQuestionPrompt_includesQuestionTypesAndCountBounds() {
        Fixture fixture = fixture();

        String prompt = promptBuilder.buildReviewQuestionPrompt(
                fixture.document(),
                fixture.node(),
                List.of(fixture.chunk()),
                15,
                20
        );

        assertThat(prompt).contains("Tao bo cau hoi on tap");
        assertThat(prompt).contains("Tao tu 15 den 20 cau");
        assertThat(prompt).contains("MULTIPLE_CHOICE");
        assertThat(prompt).contains("TRUE_FALSE");
        assertThat(prompt).contains("FILL_BLANK");
        assertThat(prompt).contains("\"correctAnswer\": true");
        assertThat(prompt).contains("chunkId: 200");
    }

    private Fixture fixture() {
        Document document = Document.builder()
                .title("Giáo trình")
                .build();
        document.setId(10L);

        DocumentNode node = DocumentNode.builder()
                .document(document)
                .nodeType("chapter")
                .title("Chương 1")
                .sectionPath("Chương 1")
                .build();
        node.setId(100L);

        DocumentChunk chunk = DocumentChunk.builder()
                .document(document)
                .node(node)
                .chunkIndex(1)
                .sourceOrder(1)
                .sectionPath("Chương 1")
                .pageFrom(3)
                .pageTo(4)
                .chunkType("TEXT")
                .content("Nội dung học tập quan trọng")
                .build();
        chunk.setId(200L);
        return new Fixture(document, node, chunk);
    }

    private record Fixture(Document document, DocumentNode node, DocumentChunk chunk) {
    }
}
