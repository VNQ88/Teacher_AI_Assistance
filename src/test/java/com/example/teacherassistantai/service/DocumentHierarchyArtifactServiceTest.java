package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.Document;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentHierarchyArtifactServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DocumentHierarchyArtifactService artifactService =
            new DocumentHierarchyArtifactService(new MarkdownChunkingService());

    @Test
    void buildArtifacts_exportsHierarchyJsonAndChunksJsonl() throws Exception {
        Document document = Document.builder()
                .title("Test document")
                .originalObjectKey("uploads/test.pdf")
                .markdownObjectKey("uploads/test.md")
                .build();
        document.setId(99L);

        DocumentHierarchyArtifactService.Artifacts artifacts = artifactService.buildArtifacts(document, """
                # Test document

                ### Chương 1: Tổng Quan

                #### I. Khái niệm

                Nội dung chính.

                TÓM TẮT CHƯƠNG 1

                ##### 1. Đây là heading giả trong tóm tắt

                CÂU HỎI ÔN TẬP CHƯƠNG 1

                1. Câu hỏi kiểm tra?
                """);

        JsonNode hierarchy = objectMapper.readTree(artifacts.hierarchyJson());
        assertThat(hierarchy.get("documentId").asLong()).isEqualTo(99L);
        assertThat(hierarchy.get("chunkCount").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(hierarchy.get("root").get("nodeType").asText()).isEqualTo("document");
        assertThat(hierarchy.get("nodes").findValuesAsText("nodeType")).doesNotContain("parent");

        String[] jsonLines = artifacts.chunksJsonl().strip().split("\\R");
        assertThat(jsonLines).isNotEmpty();
        assertThat(artifacts.chunksJsonl()).contains("\"chunkType\":\"SUMMARY\"");
        assertThat(artifacts.chunksJsonl()).contains("\"chunkType\":\"REVIEW_QUESTIONS\"");
    }
}
