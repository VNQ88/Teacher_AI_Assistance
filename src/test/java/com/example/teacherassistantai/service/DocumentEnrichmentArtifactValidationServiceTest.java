package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.service.quiz.QuizInputMode;
import com.example.teacherassistantai.service.quiz.ReviewQuestionCoverage;
import com.example.teacherassistantai.service.quiz.ReviewQuestionGenerationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentEnrichmentArtifactValidationServiceTest {

    private final DocumentEnrichmentArtifactValidationService validationService =
            new DocumentEnrichmentArtifactValidationService(new ObjectMapper());

    @Test
    void parseAndValidate_acceptsSummaryJsonFromCodeFence() {
        Fixture fixture = fixture("Ngắn.");

        Map<String, Object> content = validationService.parseAndValidate(
                DocumentNodeArtifactType.SUMMARY,
                """
                        ```json
                        {
                          "summaryMode": "CHAPTER_FALLBACK",
                          "summary": "Chương này trình bày nội dung chính.",
                          "keyPoints": ["Ý chính"],
                          "childSummaries": [],
                          "childSummaryRefs": [],
                          "citations": [{"chunkId": 200, "pageFrom": 3, "pageTo": 4}],
                          "coverage": {
                            "expectedChildCount": 0,
                            "usedChildCount": 0,
                            "missingChildNodeIds": [],
                            "directChunkCount": 1,
                            "usedDirectChunkCount": 1,
                            "complete": true
                          }
                        }
                        ```
                        """,
                fixture.node(),
                List.of(fixture.chunk()),
                15,
                20
        );

        assertThat(content).containsEntry("summary", "Chương này trình bày nội dung chính.");
        assertThat(content).containsEntry("generated", true);
        assertThat(content).containsEntry("nodeTitle", "Chương 1");
    }

    @Test
    void parseAndValidate_acceptsSummaryAliasContent() {
        Fixture fixture = fixture("Ngắn.");

        Map<String, Object> content = validationService.parseAndValidate(
                DocumentNodeArtifactType.SUMMARY,
                """
                        {
                          "summaryMode": "CHAPTER_FALLBACK",
                          "content": "Chương này trình bày nội dung chính.",
                          "keyPoints": ["Ý chính"],
                          "childSummaries": [],
                          "childSummaryRefs": [],
                          "citations": [{"chunkId": 200, "pageFrom": 3, "pageTo": 4}],
                          "coverage": {
                            "expectedChildCount": 0,
                            "usedChildCount": 0,
                            "missingChildNodeIds": [],
                            "directChunkCount": 1,
                            "usedDirectChunkCount": 1,
                            "complete": true
                          }
                        }
                        """,
                fixture.node(),
                List.of(fixture.chunk()),
                15,
                20
        );

        assertThat(content).containsEntry("summary", "Chương này trình bày nội dung chính.");
    }

    @Test
    void parseAndValidate_rejectsCitationOutsideScope() {
        Fixture fixture = fixture("Ngắn.");

        assertThatThrownBy(() -> validationService.parseAndValidate(
                DocumentNodeArtifactType.SUMMARY,
                """
                        {
                          "summaryMode": "CHAPTER_FALLBACK",
                          "summary": "Tóm tắt.",
                          "keyPoints": ["Ý chính"],
                          "childSummaries": [],
                          "childSummaryRefs": [],
                          "citations": [{"chunkId": 999}],
                          "coverage": {
                            "expectedChildCount": 0,
                            "usedChildCount": 0,
                            "missingChildNodeIds": [],
                            "directChunkCount": 1,
                            "usedDirectChunkCount": 1,
                            "complete": true
                          }
                        }
                        """,
                fixture.node(),
                List.of(fixture.chunk()),
                15,
                20
        ))
                .isInstanceOf(InvalidDataException.class)
                .hasMessageContaining("outside direct chunk scope");
    }

    @Test
    void parseAndValidateSummary_injectsParentMetadataAndIgnoresLlmChildMetadata() {
        Fixture fixture = fixture("Ngắn.");
        ChildSummary childSummary = childSummary();

        Map<String, Object> content = validationService.parseAndValidateSummary(
                """
                        {
                          "summaryMode": "CHAPTER_FROM_SECTIONS",
                          "summary": "Chương này tổng hợp các section.",
                          "keyPoints": ["Ý chính 1", "Ý chính 2"],
                          "childSummaries": [],
                          "childSummaryRefs": [],
                          "citations": [{"chunkId": 999}],
                          "coverage": {
                            "expectedChildCount": 0,
                            "usedChildCount": 0,
                            "missingChildNodeIds": [],
                            "directChunkCount": 0,
                            "usedDirectChunkCount": 0,
                            "complete": true
                          }
                        }
	                """,
                fixture.node(),
                List.of(),
                List.of(childSummary),
                new SummaryCoverage(1, 1, List.of(), 0, 0, true),
                SummaryMode.CHAPTER_FROM_SECTIONS
        );

        assertThat(content).containsEntry("summaryMode", "CHAPTER_FROM_SECTIONS");
        assertThat(content).containsEntry("generated", true);
        assertThat((List<?>) content.get("citations")).isEmpty();

        Map<?, ?> coverage = (Map<?, ?>) content.get("coverage");
        assertThat(coverage.get("expectedChildCount")).isEqualTo(1);
        assertThat(coverage.get("usedChildCount")).isEqualTo(1);

        List<?> childSummaries = (List<?>) content.get("childSummaries");
        assertThat(childSummaries).hasSize(1);
        Map<?, ?> injectedChildSummary = (Map<?, ?>) childSummaries.getFirst();
        assertThat(injectedChildSummary.get("nodeId")).isEqualTo(101L);
        assertThat(injectedChildSummary.get("summary")).isEqualTo("Tóm tắt section.");

        List<?> childSummaryRefs = (List<?>) content.get("childSummaryRefs");
        assertThat(childSummaryRefs).hasSize(1);
        Map<?, ?> injectedRef = (Map<?, ?>) childSummaryRefs.getFirst();
        assertThat(injectedRef.get("artifactId")).isEqualTo(901L);
        assertThat(injectedRef.get("sourceHash")).isEqualTo("hash-1");
    }

    @Test
    void parseAndValidateSummary_rejectsIncompleteExpectedCoverage() {
        Fixture fixture = fixture("Ngắn.");

        assertThatThrownBy(() -> validationService.parseAndValidateSummary(
                """
                        {
                          "summaryMode": "CHAPTER_FROM_SECTIONS",
                          "summary": "Thiếu một section.",
                          "keyPoints": ["Ý chính"],
                          "childSummaries": [],
                          "childSummaryRefs": [],
                          "citations": [],
                          "coverage": {
                            "expectedChildCount": 1,
                            "usedChildCount": 0,
                            "missingChildNodeIds": [101],
                            "directChunkCount": 0,
                            "usedDirectChunkCount": 0,
                            "complete": false
                          }
                        }
                        """,
                fixture.node(),
                List.of(),
                List.of(),
                new SummaryCoverage(1, 0, List.of(101L), 0, 0, false),
                SummaryMode.CHAPTER_FROM_SECTIONS
        ))
                .isInstanceOf(InvalidDataException.class)
                .hasMessageContaining("expected summary coverage must be complete");
    }

    @Test
    void parseAndValidateSummary_overwritesWrongCoverageWithExpectedCoverage() {
        Fixture fixture = fixture("Ngắn.");

        Map<String, Object> content = validationService.parseAndValidateSummary(
                """
                        {
                          "summaryMode": "CHAPTER_FALLBACK",
                          "summary": "Tóm tắt từ chunk.",
                          "keyPoints": ["Ý chính"],
                          "citations": [{"chunkId": 200}],
                          "coverage": {
                            "expectedChildCount": 99,
                            "usedChildCount": 99,
                            "missingChildNodeIds": [],
                            "directChunkCount": 99,
                            "usedDirectChunkCount": 99,
                            "complete": true
                          }
                        }
                        """,
                fixture.node(),
                List.of(fixture.chunk()),
                List.of(),
                SummaryCoverage.chunksOnly(1, 1),
                SummaryMode.CHAPTER_FALLBACK
        );

        Map<?, ?> coverage = (Map<?, ?>) content.get("coverage");
        assertThat(coverage.get("expectedChildCount")).isEqualTo(0);
        assertThat(coverage.get("usedChildCount")).isEqualTo(0);
        assertThat(coverage.get("directChunkCount")).isEqualTo(1);
        assertThat(coverage.get("usedDirectChunkCount")).isEqualTo(1);
    }

    @Test
    void parseAndValidate_rejectsQuestionCountOutsideRangeWhenContextIsEnough() {
        Fixture fixture = fixture("Nội dung đủ dài. ".repeat(100));

        assertThatThrownBy(() -> validationService.parseAndValidate(
                DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                """
                        {
                          "questionCount": 1,
                          "questions": [
                            {
                              "type": "TRUE_FALSE",
                              "difficulty": "EASY",
                              "question": "Nhận định này đúng hay sai?",
                              "correctAnswer": true,
                              "answerExplanation": "Dựa trên tài liệu.",
                              "citations": [{"chunkId": 200}]
                            }
                          ]
                        }
                        """,
                fixture.node(),
                List.of(fixture.chunk()),
                15,
                20
        ))
                .isInstanceOf(InvalidDataException.class)
                .hasMessageContaining("between 15 and 20");
    }

    @Test
    void parseAndValidate_acceptsSubsectionLevel2QuestionCountRangeAndAddsMetadata() {
        Fixture fixture = fixture("Nội dung đủ dài. ".repeat(100));

        Map<String, Object> content = validationService.parseAndValidate(
                DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                reviewQuestionJson(7),
                fixture.node(),
                List.of(fixture.chunk()),
                5,
                10
        );

        assertThat(content).containsEntry("questionCount", 7);
        assertThat(content).containsEntry("requestedQuestionMinCount", 5);
        assertThat(content).containsEntry("requestedQuestionMaxCount", 10);
    }

    @Test
    void parseAndValidate_rejectsSubsectionLevel2QuestionCountAboveMax() {
        Fixture fixture = fixture("Nội dung đủ dài. ".repeat(100));

        assertThatThrownBy(() -> validationService.parseAndValidate(
                DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                reviewQuestionJson(12),
                fixture.node(),
                List.of(fixture.chunk()),
                5,
                10
        ))
                .isInstanceOf(InvalidDataException.class)
                .hasMessageContaining("between 5 and 10");
    }

    @Test
    void parseAndValidate_acceptsSectionQuestionCountRange() {
        Fixture fixture = fixture("Nội dung đủ dài. ".repeat(100));

        Map<String, Object> content = validationService.parseAndValidate(
                DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                reviewQuestionJson(18),
                fixture.node(),
                List.of(fixture.chunk()),
                15,
                20
        );

        assertThat(content).containsEntry("questionCount", 18);
    }

    @Test
    void parseAndValidate_acceptsMismatchedDeclaredQuestionCountAndNormalizesIt() {
        Fixture fixture = fixture("Ngắn.");

        Map<String, Object> content = validationService.parseAndValidate(
                DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                """
                        {
                          "questionCount": 20,
                          "questions": [
                            {
                              "type": "TRUE_FALSE",
                              "difficulty": "EASY",
                              "question": "Nhận định này đúng hay sai?",
                              "correctAnswer": true,
                              "answerExplanation": "Dựa trên tài liệu.",
                              "citations": [{"chunkId": 200}]
                            }
                          ]
                        }
                        """,
                fixture.node(),
                List.of(fixture.chunk()),
                15,
                20
        );

        assertThat(content).containsEntry("questionCount", 1);
    }

    @Test
    void parseAndValidate_acceptsReviewQuestionAliasItemsAndNormalizesIt() {
        Fixture fixture = fixture("Ngắn.");

        Map<String, Object> content = validationService.parseAndValidate(
                DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                """
                        {
                          "items": [
                            {
                              "type": "TRUE_FALSE",
                              "difficulty": "EASY",
                              "question": "Nhận định này đúng hay sai?",
                              "correctAnswer": true,
                              "answerExplanation": "Dựa trên tài liệu.",
                              "citations": [{"chunkId": 200}]
                            }
                          ]
                        }
                        """,
                fixture.node(),
                List.of(fixture.chunk()),
                15,
                20
        );

        assertThat(content).containsKey("questions");
        assertThat(content).containsEntry("questionCount", 1);
    }

    @Test
    void parseAndValidate_rejectsTrueFalseAnswerWhenNotBoolean() {
        Fixture fixture = fixture("Ngắn.");

        assertThatThrownBy(() -> validationService.parseAndValidate(
                DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                """
                        {
                          "questionCount": 1,
                          "questions": [
                            {
                              "type": "TRUE_FALSE",
                              "difficulty": "EASY",
                              "question": "Nhận định này đúng hay sai?",
                              "correctAnswer": "true",
                              "answerExplanation": "Dựa trên tài liệu.",
                              "citations": [{"chunkId": 200}]
                            }
                          ]
                        }
                        """,
                fixture.node(),
                List.of(fixture.chunk()),
                15,
                20
        ))
                .isInstanceOf(InvalidDataException.class)
                .hasMessageContaining("correctAnswer must be boolean");
    }

    @Test
    void parseAndValidateReviewQuestions_acceptsMixedSourceModesAndAllowedCitationChunks() {
        Fixture fixture = fixture("Nội dung đủ dài. ".repeat(100));

        Map<String, Object> content = validationService.parseAndValidateReviewQuestions(
                """
                        {
                          "questions": [
                            {
                              "sourceMode": "CHILD_SUMMARY",
                              "type": "TRUE_FALSE",
                              "difficulty": "EASY",
                              "question": "Nhận định này đúng hay sai?",
                              "correctAnswer": true,
                              "answerExplanation": "Dựa trên tài liệu.",
                              "citations": [{"chunkId": 200}]
                            }
                          ]
                        }
                        """,
                mixedContext(fixture)
        );

        assertThat(content).containsEntry("inputMode", "MIXED_CHILD_SUMMARIES_AND_REPRESENTATIVE_CHUNKS");
        assertThat(content).containsEntry("summaryBasedTargetCount", 1);
        assertThat(content).containsEntry("representativeTargetCount", 1);
        assertThat(content).containsKey("coverage");
    }

    @Test
    void parseAndValidateReviewQuestions_rejectsMissingSourceModeInMixedMode() {
        Fixture fixture = fixture("Ngắn.");

        assertThatThrownBy(() -> validationService.parseAndValidateReviewQuestions(
                """
                        {
                          "questions": [
                            {
                              "type": "TRUE_FALSE",
                              "difficulty": "EASY",
                              "question": "Nhận định này đúng hay sai?",
                              "correctAnswer": true,
                              "answerExplanation": "Dựa trên tài liệu.",
                              "citations": [{"chunkId": 200}]
                            }
                          ]
                        }
                        """,
                mixedContext(fixture)
        ))
                .isInstanceOf(InvalidDataException.class)
                .hasMessageContaining("sourceMode is required");
    }

    @Test
    void parseAndValidate_escapesRawNewlineInsideJsonString() {
        Fixture fixture = fixture("Ngắn.");

        Map<String, Object> content = validationService.parseAndValidate(
                DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                """
                        {
                          "questions": [
                            {
                              "type": "TRUE_FALSE",
                              "difficulty": "EASY",
                              "question": "Dòng 1
                        Dòng 2",
                              "correctAnswer": true,
                              "answerExplanation": "Dựa trên tài liệu.",
                              "citations": [{"chunkId": 200}]
                            }
                          ]
                        }
                        """,
                fixture.node(),
                List.of(fixture.chunk()),
                1,
                3
        );

        assertThat(content).containsEntry("questionCount", 1);
    }

    private Fixture fixture(String content) {
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
                .content(content)
                .build();
        chunk.setId(200L);
        return new Fixture(document, node, chunk);
    }

    private String reviewQuestionJson(int count) {
        StringBuilder questions = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                questions.append(",");
            }
            questions.append("""
                    {
                      "type": "TRUE_FALSE",
                      "difficulty": "EASY",
                      "question": "Nhận định %d đúng hay sai?",
                      "correctAnswer": true,
                      "answerExplanation": "Dựa trên tài liệu.",
                      "citations": [{"chunkId": 200}]
                    }
                    """.formatted(i));
        }
        return """
                {
                  "questions": [%s]
                }
                """.formatted(questions);
    }

    private ReviewQuestionGenerationContext mixedContext(Fixture fixture) {
        return new ReviewQuestionGenerationContext(
                fixture.node().getDocument(),
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
                        "Tóm tắt section đủ dài. ".repeat(100),
                        List.of(Map.of("chunkId", 200L))
                )),
                Map.of(),
                Map.of(101L, List.of(fixture.chunk())),
                List.of(fixture.chunk()),
                1,
                3,
                1,
                1,
                new ReviewQuestionCoverage(1, 1, 0, 1, 0, 1, true),
                "hash"
        );
    }

    private ChildSummary childSummary() {
        return new ChildSummary(
                101L,
                "section",
                "1.1",
                "Chương 1 > 1.1",
                901L,
                "hash-1",
                "Tóm tắt section.",
                List.of(Map.of("chunkId", 200L))
        );
    }

    private record Fixture(Document document, DocumentNode node, DocumentChunk chunk) {
    }
}
