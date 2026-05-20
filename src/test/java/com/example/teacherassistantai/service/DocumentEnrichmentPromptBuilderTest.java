package com.example.teacherassistantai.service;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.service.quiz.QuizInputMode;
import com.example.teacherassistantai.service.quiz.ReviewQuestionCoverage;
import com.example.teacherassistantai.service.quiz.ReviewQuestionGenerationContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
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
        assertThat(prompt).contains("\"summaryMode\": \"CHAPTER_FALLBACK\"");
        assertThat(prompt).contains("\"summary\": \"string\"");
        assertThat(prompt).contains("Backend se tu them metadata node, coverage va child summary refs");
        assertThat(prompt).doesNotContain("\"coverage\"");
        assertThat(prompt).doesNotContain("\"childSummaries\"");
        assertThat(prompt).doesNotContain("\"childSummaryRefs\"");
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
    void buildDocumentSummaryPrompt_requiresLongerOverviewKeyPointsAndBestEffortInputs() {
        Fixture fixture = fixture();
        DocumentNode documentRoot = DocumentNode.builder()
                .document(fixture.document())
                .nodeType("document")
                .title("Giáo trình")
                .sectionPath("Giáo trình")
                .build();
        documentRoot.setId(99L);
        Map<Long, List<DocumentChunk>> fallbackRawChunks = new LinkedHashMap<>();
        fallbackRawChunks.put(103L, List.of(fixture.chunk()));
        SummaryGenerationContext context = new SummaryGenerationContext(
                fixture.document(),
                documentRoot,
                SummaryMode.DOCUMENT_FROM_CHAPTERS,
                List.of(),
                List.of(
                        new ChildSummary(
                                101L,
                                "chapter",
                                "Chương 1",
                                "Chương 1",
                                901L,
                                "hash-1",
                                "Tóm tắt chương 1.",
                                List.of(Map.of("chunkId", 200L))
                        ),
                        new ChildSummary(
                                102L,
                                "chapter",
                                "Chương 2",
                                "Chương 2",
                                902L,
                                "hash-2",
                                "Tóm tắt chương 2.",
                                List.of()
                        )
                ),
                new SummaryCoverage(3, 2, List.of(), 0, 0, true, 1),
                fallbackRawChunks
        );

        String prompt = promptBuilder.buildDocumentSummaryPrompt(context);

        assertThat(prompt).contains("Tao summary document/mon hoc");
        assertThat(prompt).contains("Khong chi liet ke part/chapter");
        assertThat(prompt).contains("2-3 doan");
        assertThat(prompt).contains("moi doan 4-6 cau");
        assertThat(prompt).contains("8-12 keyPoints");
        assertThat(prompt).contains("summaryMode bat buoc: DOCUMENT_FROM_CHAPTERS");
        assertThat(prompt).contains("expectedChildCount: 3");
        assertThat(prompt).contains("fallbackChildCount: 1");
        assertThat(prompt).contains("<<<CHILD_SUMMARIES>>>");
        assertThat(prompt).contains("Tóm tắt chương 1.");
        assertThat(prompt).contains("Tóm tắt chương 2.");
        assertThat(prompt).contains("<<<FALLBACK_CHUNKS>>>");
        assertThat(prompt).contains("childNodeId: 103");
        assertThat(prompt).contains("Nội dung học tập quan trọng");
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
        assertThat(prompt).contains("question, options, correctAnswer va answerExplanation khong duoc nhac chunkId/chunk");
        assertThat(prompt).contains("chunkId: 200");
    }

    @Test
    void buildReviewQuestionPrompt_mixedInputIncludesSourceBlocksAndTargets() {
        Fixture fixture = fixture();
        Map<Long, List<DocumentChunk>> fallback = new LinkedHashMap<>();
        fallback.put(101L, List.of(fixture.chunk()));
        Map<Long, List<DocumentChunk>> representative = new LinkedHashMap<>();
        representative.put(101L, List.of(fixture.chunk()));
        ReviewQuestionGenerationContext context = new ReviewQuestionGenerationContext(
                fixture.document(),
                fixture.node(),
                QuizInputMode.MIXED_CHILD_SUMMARIES_AND_REPRESENTATIVE_CHUNKS,
                List.of(),
                List.of(new ChildSummary(
                        101L,
                        "section",
                        "1.1",
                        "Chương 1 > 1.1",
                        901L,
                        "hash-1",
                        "Tóm tắt section.",
                        List.of(Map.of("chunkId", 200L))
                )),
                fallback,
                representative,
                List.of(fixture.chunk()),
                20,
                25,
                12,
                13,
                new ReviewQuestionCoverage(1, 1, 0, 1, 0, 1, true),
                "hash"
        );

        String prompt = promptBuilder.buildReviewQuestionPrompt(fixture.document(), context);

        assertThat(prompt).contains("Khoang 12 cau");
        assertThat(prompt).contains("Khoang 13 cau");
        assertThat(prompt).contains("<<<CHILD_SUMMARIES>>>");
        assertThat(prompt).contains("<<<FALLBACK_CHUNKS>>>");
        assertThat(prompt).contains("<<<REPRESENTATIVE_CHILD_CHUNKS>>>");
        assertThat(prompt).contains("\"sourceMode\"");
        assertThat(prompt).contains("Allowed citation chunkIds");
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
