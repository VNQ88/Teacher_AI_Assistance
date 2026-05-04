package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentHierarchyArtifactValidationServiceTest {

    private final DocumentHierarchyArtifactService artifactService =
            new DocumentHierarchyArtifactService(new MarkdownChunkingService());
    private final DocumentHierarchyArtifactValidationService validationService =
            new DocumentHierarchyArtifactValidationService(new ObjectMapper());

    @Test
    void validate_acceptsGeneratedArtifactsAndReportsWarningsOnly() {
        Document document = Document.builder()
                .title("Test document")
                .originalObjectKey("uploads/test.pdf")
                .markdownObjectKey("uploads/test.md")
                .fileType("PDF")
                .build();
        document.setId(10L);

        DocumentHierarchyArtifactService.Artifacts artifacts = artifactService.buildArtifacts(document, """
                <!-- page: 1 -->

                ### Chương 1: Tổng quan

                #### I. Khái niệm

                Nội dung chính.
                """);

        DocumentHierarchyArtifactValidationService.ValidationReport report =
                validationService.validate(document, artifacts);

        assertThat(report.errorCount()).isZero();
    }

    @Test
    void validate_rejectsPlaceholderNodes() {
        Document document = Document.builder()
                .title("Bad document")
                .fileType("PDF")
                .build();
        document.setId(11L);

        DocumentHierarchyArtifactService.Artifacts artifacts = new DocumentHierarchyArtifactService.Artifacts(
                null,
                "",
                java.util.List.of(),
                """
                        {"root":{"nodeId":"n0"},"nodes":[{"nodeId":"n1","nodeType":"parent","title":"placeholder"}]}
                        """,
                """
                        {"nodeId":"n1","parentNodeId":"n0","breadcrumb":["A"],"content":"B"}
                        """
        );

        assertThatThrownBy(() -> validationService.validate(document, artifacts))
                .isInstanceOf(InvalidDataException.class)
                .hasMessageContaining("placeholderCount=1");
    }
}
