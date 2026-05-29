package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.integration.ai.AiChatCompletion;
import com.example.teacherassistantai.integration.ai.AiChatGateway;
import com.example.teacherassistantai.integration.ai.AiUsage;
import com.example.teacherassistantai.integration.ai.AiWorkload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmDocumentNodeArtifactGeneratorTest {

    @Test
    void generate_returnsValidatedSummaryContent() {
        LlmDocumentNodeArtifactGenerator generator = generator((prompt, temperature, workload) -> """
                {
                  "summaryMode": "CHAPTER_FALLBACK",
                  "summary": "Tóm tắt hợp lệ.",
                  "keyPoints": ["Ý chính"],
                  "childSummaries": [],
                  "childSummaryRefs": [],
                  "citations": [{"chunkId": 200}],
                  "coverage": {
                    "expectedChildCount": 0,
                    "usedChildCount": 0,
                    "missingChildNodeIds": [],
                    "directChunkCount": 1,
                    "usedDirectChunkCount": 1,
                    "complete": true
                  }
                }
                """);
        Fixture fixture = fixture();

        DocumentNodeArtifactGenerationResult result = generator.generate(new DocumentNodeArtifactGenerationContext(
                fixture.document(),
                fixture.node(),
                DocumentNodeArtifactType.SUMMARY,
                List.of(fixture.chunk()),
                "hash",
                "enrichment-v1",
                "openai-gpt-oss-120b",
                15,
                20,
                60000
        ));

        assertThat(result.contentJsonb()).containsEntry("summary", "Tóm tắt hợp lệ.");
        assertThat(result.contentJsonb()).containsEntry("generated", true);
        assertThat(result.tokenCount()).isPositive();
    }

    @Test
    void generate_throwsWhenLlmJsonFailsValidation() {
        LlmDocumentNodeArtifactGenerator generator = generator((prompt, temperature, workload) -> """
                {
                  "summaryMode": "CHAPTER_FALLBACK",
                  "summary": "",
                  "keyPoints": ["Ý chính"],
                  "childSummaries": [],
                  "childSummaryRefs": [],
                  "citations": [{"chunkId": 200}],
                  "coverage": {
                    "expectedChildCount": 0,
                    "usedChildCount": 0,
                    "missingChildNodeIds": [],
                    "directChunkCount": 1,
                    "usedDirectChunkCount": 1,
                    "complete": true
                  }
                }
                """);
        Fixture fixture = fixture();

        assertThatThrownBy(() -> generator.generate(new DocumentNodeArtifactGenerationContext(
                fixture.document(),
                fixture.node(),
                DocumentNodeArtifactType.SUMMARY,
                List.of(fixture.chunk()),
                "hash",
                "enrichment-v1",
                "openai-gpt-oss-120b",
                15,
                20,
                60000
        )))
                .isInstanceOf(InvalidDataException.class)
                .hasMessageContaining("Summary is required");
    }

    @Test
    void generate_repairsInvalidJsonOnceBeforeFailingArtifact() {
        AtomicInteger callCount = new AtomicInteger();
        LlmDocumentNodeArtifactGenerator generator = generator((prompt, temperature, workload) -> {
            if (callCount.incrementAndGet() == 1) {
                return """
                        {
                          "title": "Sai schema",
                          "citations": [{"chunkId": 200}]
                        }
                        """;
            }
            return """
                    {
                      "summaryMode": "CHAPTER_FALLBACK",
                      "summary": "Tóm tắt sau khi repair.",
                      "keyPoints": ["Ý chính"],
                      "childSummaries": [],
                      "childSummaryRefs": [],
                      "citations": [{"chunkId": 200}],
                      "coverage": {
                        "expectedChildCount": 0,
                        "usedChildCount": 0,
                        "missingChildNodeIds": [],
                        "directChunkCount": 1,
                        "usedDirectChunkCount": 1,
                        "complete": true
                      }
                    }
                    """;
        });
        Fixture fixture = fixture();

        DocumentNodeArtifactGenerationResult result = generator.generate(new DocumentNodeArtifactGenerationContext(
                fixture.document(),
                fixture.node(),
                DocumentNodeArtifactType.SUMMARY,
                List.of(fixture.chunk()),
                "hash",
                "enrichment-v1",
                "openai-gpt-oss-120b",
                15,
                20,
                60000
        ));

        assertThat(callCount).hasValue(2);
        assertThat(result.contentJsonb()).containsEntry("summary", "Tóm tắt sau khi repair.");
    }

    @Test
    void generateSummary_usesEnrichSummaryWorkloadAndUsageTokenCount() {
        AtomicInteger calls = new AtomicInteger();
        LlmDocumentNodeArtifactGenerator generator = generator(new AiChatGateway() {
            @Override
            public String generateAnswer(String prompt, Double temperature, AiWorkload workload) {
                throw new AssertionError("generate should be used");
            }

            @Override
            public AiChatCompletion generate(String prompt, Double temperature, AiWorkload workload) {
                calls.incrementAndGet();
                assertThat(workload).isEqualTo(AiWorkload.ENRICH_SUMMARY);
                return new AiChatCompletion("""
                        {
                          "summaryMode": "CHAPTER_FALLBACK",
                          "summary": "Tóm tắt hợp lệ.",
                          "keyPoints": ["Ý chính"],
                          "childSummaries": [],
                          "childSummaryRefs": [],
                          "citations": [{"chunkId": 200}],
                          "coverage": {
                            "expectedChildCount": 0,
                            "usedChildCount": 0,
                            "missingChildNodeIds": [],
                            "directChunkCount": 1,
                            "usedDirectChunkCount": 1,
                            "complete": true
                          }
                        }
                        """, new AiUsage(10, 20, 30), null, "summary-model", workload);
            }
        });
        Fixture fixture = fixture();

        DocumentNodeArtifactGenerationResult result = generator.generateSummary(new SummaryGenerationContext(
                fixture.document(),
                fixture.node(),
                SummaryMode.CHAPTER_FALLBACK,
                List.of(fixture.chunk()),
                List.of(),
                SummaryCoverage.chunksOnly(1, 1)
        ));

        assertThat(calls).hasValue(1);
        assertThat(result.tokenCount()).isEqualTo(30);
    }

    @Test
    void generateSummary_documentModeUsesDocumentPrompt() {
        LlmDocumentNodeArtifactGenerator generator = generator(new AiChatGateway() {
            @Override
            public String generateAnswer(String prompt, Double temperature, AiWorkload workload) {
                throw new AssertionError("generate should be used");
            }

            @Override
            public AiChatCompletion generate(String prompt, Double temperature, AiWorkload workload) {
                assertThat(workload).isEqualTo(AiWorkload.ENRICH_SUMMARY);
                assertThat(prompt).contains("Tao summary document/mon hoc");
                assertThat(prompt).contains("Khong chi liet ke part/chapter");
                assertThat(prompt).contains("summaryMode bat buoc: DOCUMENT_FROM_CHAPTERS");
                return new AiChatCompletion("""
                        {
                          "summaryMode": "DOCUMENT_FROM_CHAPTERS",
                          "summary": "Tóm tắt toàn bộ tài liệu.",
                          "keyPoints": ["Ý chính tài liệu"],
                          "citations": []
                        }
                        """, null, null, "summary-model", workload);
            }
        });
        Fixture fixture = fixture();
        DocumentNode documentRoot = DocumentNode.builder()
                .document(fixture.document())
                .nodeType("document")
                .title("Giáo trình")
                .sectionPath("Giáo trình")
                .build();
        documentRoot.setId(99L);

        DocumentNodeArtifactGenerationResult result = generator.generateSummary(new SummaryGenerationContext(
                fixture.document(),
                documentRoot,
                SummaryMode.DOCUMENT_FROM_CHAPTERS,
                List.of(),
                List.of(new ChildSummary(
                        101L,
                        "chapter",
                        "Chương 1",
                        "Chương 1",
                        901L,
                        "hash-1",
                        "Tóm tắt chương 1.",
                        List.of(Map.of("chunkId", 200L))
                )),
                new SummaryCoverage(1, 1, List.of(), 0, 0, true)
        ));

        assertThat(result.contentJsonb()).containsEntry("summaryMode", SummaryMode.DOCUMENT_FROM_CHAPTERS.name());
        assertThat(result.contentJsonb()).containsEntry("summary", "Tóm tắt toàn bộ tài liệu.");
        assertThat(result.contentJsonb()).containsEntry("nodeType", "document");
    }

    @Test
    void generateReviewQuestions_usesReviewQuestionWorkload() {
        LlmDocumentNodeArtifactGenerator generator = generator(new AiChatGateway() {
            @Override
            public String generateAnswer(String prompt, Double temperature, AiWorkload workload) {
                throw new AssertionError("generate should be used");
            }

            @Override
            public AiChatCompletion generate(String prompt, Double temperature, AiWorkload workload) {
                assertThat(workload).isEqualTo(AiWorkload.ENRICH_REVIEW_QUESTION);
                return new AiChatCompletion("""
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
                        """, null, null, "question-model", workload);
            }
        });
        Fixture fixture = fixture();

        DocumentNodeArtifactGenerationResult result = generator.generate(new DocumentNodeArtifactGenerationContext(
                fixture.document(),
                fixture.node(),
                DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                List.of(fixture.chunk()),
                "hash",
                "enrichment-v1",
                "question-model",
                1,
                3,
                60000
        ));

        assertThat(result.contentJsonb()).containsEntry("questionCount", 1);
    }

    private LlmDocumentNodeArtifactGenerator generator(AiChatGateway aiChatGateway) {
        return new LlmDocumentNodeArtifactGenerator(
                aiChatGateway,
                new DocumentEnrichmentPromptBuilder(new RagProperties()),
                new DocumentEnrichmentArtifactValidationService(new ObjectMapper()),
                new RagProperties()
        );
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
