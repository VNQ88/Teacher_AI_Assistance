package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.integration.ai.AiChatGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmDocumentNodeArtifactGeneratorTest {

    @Test
    void generate_returnsValidatedSummaryContent() {
        LlmDocumentNodeArtifactGenerator generator = generator((prompt, temperature) -> """
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
        LlmDocumentNodeArtifactGenerator generator = generator((prompt, temperature) -> """
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
        LlmDocumentNodeArtifactGenerator generator = generator((prompt, temperature) -> {
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

    private LlmDocumentNodeArtifactGenerator generator(AiChatGateway aiChatGateway) {
        return new LlmDocumentNodeArtifactGenerator(
                aiChatGateway,
                new DocumentEnrichmentPromptBuilder(new RagProperties()),
                new DocumentEnrichmentArtifactValidationService(new ObjectMapper())
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
