package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.exception.InvalidDataException;
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
    void parseAndValidateSummary_acceptsParentSummaryWithChildRefs() {
        Fixture fixture = fixture("Ngắn.");

        Map<String, Object> content = validationService.parseAndValidateSummary(
                """
                        {
                          "nodeTitle": "Chương 1",
                          "sectionPath": "Chương 1",
                          "nodeType": "chapter",
                          "summaryMode": "CHAPTER_FROM_SECTIONS",
                          "summary": "Chương này tổng hợp các section.",
                          "keyPoints": ["Ý chính 1", "Ý chính 2"],
                          "childSummaries": [
                            {
                              "nodeId": 101,
                              "nodeType": "section",
                              "title": "1.1",
                              "sectionPath": "Chương 1 > 1.1",
                              "summary": "Tóm tắt section."
                            }
                          ],
                          "childSummaryRefs": [
                            {"nodeId": 101, "artifactId": 901, "sourceHash": "hash-1"}
                          ],
                          "citations": [],
                          "coverage": {
                            "expectedChildCount": 1,
                            "usedChildCount": 1,
                            "missingChildNodeIds": [],
                            "directChunkCount": 0,
                            "usedDirectChunkCount": 0,
                            "complete": true
                          }
                        }
                        """,
                fixture.node(),
                List.of(),
                List.of(new ChildSummary(
                        101L,
                        "section",
                        "1.1",
                        "Chương 1 > 1.1",
                        901L,
                        "hash-1",
                        "Tóm tắt section.",
                        List.of()
                )),
                new SummaryCoverage(1, 1, List.of(), 0, 0, true),
                SummaryMode.CHAPTER_FROM_SECTIONS
        );

        assertThat(content).containsEntry("summaryMode", "CHAPTER_FROM_SECTIONS");
        assertThat(content).containsEntry("generated", true);
    }

    @Test
    void parseAndValidateSummary_rejectsIncompleteCoverage() {
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
                .hasMessageContaining("coverage.complete");
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

    private record Fixture(Document document, DocumentNode node, DocumentChunk chunk) {
    }
}
