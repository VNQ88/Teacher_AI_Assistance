package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
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

    @Test
    void validate_doesNotLogWarningDetailsAtDefaultLevel(CapturedOutput output) {
        Document document = Document.builder()
                .title("Warning document")
                .fileType("TXT")
                .build();
        document.setId(12L);

        DocumentHierarchyArtifactService.Artifacts artifacts = new DocumentHierarchyArtifactService.Artifacts(
                null,
                "",
                java.util.List.of(),
                """
                        {"root":{"nodeId":"n0"},"nodes":[{"nodeId":"n1","nodeType":"section","title":"Chapter 1"}]}
                        """,
                """
                        {"nodeId":"n1","parentNodeId":"n0","breadcrumb":["Chapter 1"],"sectionHeader":"Heading. Body sentence","content":"Body"}
                        """
        );

        DocumentHierarchyArtifactValidationService.ValidationReport report =
                validationService.validate(document, artifacts);

        assertThat(report.warningCount()).isEqualTo(1);
        assertThat(output).doesNotContain("Document hierarchy artifact validation warnings");
        assertThat(output).doesNotContain("Heading. Body sentence");
    }
}
