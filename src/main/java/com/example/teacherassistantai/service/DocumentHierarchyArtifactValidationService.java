package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class DocumentHierarchyArtifactValidationService {

    private static final int LONG_HEADING_THRESHOLD = 180;
    private static final int MAX_CHUNK_LENGTH = 1_800;

    private final ObjectMapper objectMapper;

    public DocumentHierarchyArtifactValidationService() {
        this(new ObjectMapper());
    }

    DocumentHierarchyArtifactValidationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ValidationReport validate(Document document,
                                     DocumentHierarchyArtifactService.Artifacts artifacts) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        JsonNode hierarchy = readJson(artifacts.hierarchyJson(), "hierarchy.json", errors);
        if (hierarchy != null) {
            validateHierarchy(hierarchy, errors, warnings);
        }
        validateChunksJsonl(document, artifacts.chunksJsonl(), errors, warnings);
        validateGeneratedChunks(document, artifacts.chunks(), warnings);

        ValidationReport report = new ValidationReport(errors.size(), warnings.size(), List.copyOf(errors), List.copyOf(warnings));
        if (!errors.isEmpty()) {
            throw new InvalidDataException("Invalid hierarchy artifacts for document id=%s: %s"
                    .formatted(document.getId(), String.join("; ", errors)));
        }
        return report;
    }

    private JsonNode readJson(String json, String artifactName, List<String> errors) {
        if (!StringUtils.hasText(json)) {
            errors.add(artifactName + " is empty");
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (IOException ex) {
            errors.add(artifactName + " is not valid JSON: " + ex.getMessage());
            return null;
        }
    }

    private void validateHierarchy(JsonNode hierarchy, List<String> errors, List<String> warnings) {
        if (!hierarchy.hasNonNull("root")) {
            errors.add("hierarchy.json missing root");
        }
        JsonNode nodes = hierarchy.path("nodes");
        if (!nodes.isArray()) {
            errors.add("hierarchy.json nodes must be an array");
            return;
        }
        if (nodes.isEmpty()) {
            errors.add("hierarchy.json nodes is empty");
        }

        int placeholderCount = 0;
        for (JsonNode node : nodes) {
            String nodeType = node.path("nodeType").asText("");
            String title = node.path("title").asText("");
            if ("parent".equalsIgnoreCase(nodeType) || title.toLowerCase(Locale.ROOT).contains("placeholder")) {
                placeholderCount++;
            }
            if (title.length() > LONG_HEADING_THRESHOLD) {
                warnings.add("long heading in hierarchy: " + abbreviate(title));
            }
            if (!node.hasNonNull("nodeId")) {
                errors.add("hierarchy node missing nodeId");
            }
        }
        if (placeholderCount > 0) {
            errors.add("hierarchy placeholderCount=" + placeholderCount);
        }
    }

    private void validateChunksJsonl(Document document,
                                     String chunksJsonl,
                                     List<String> errors,
                                     List<String> warnings) {
        if (!StringUtils.hasText(chunksJsonl)) {
            errors.add("chunks.jsonl is empty");
            return;
        }

        String[] lines = chunksJsonl.strip().split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!StringUtils.hasText(line)) {
                continue;
            }
            JsonNode chunk = readJson(line, "chunks.jsonl line " + (i + 1), errors);
            if (chunk == null) {
                continue;
            }
            if (!chunk.hasNonNull("nodeId")) {
                errors.add("chunk line " + (i + 1) + " missing nodeId");
            }
            if (!chunk.hasNonNull("parentNodeId")) {
                warnings.add("chunk line " + (i + 1) + " missing parentNodeId");
            }
            JsonNode breadcrumb = chunk.path("breadcrumb");
            if (!breadcrumb.isArray() || breadcrumb.isEmpty()) {
                errors.add("chunk line " + (i + 1) + " missing breadcrumb");
            }
            String sectionHeader = chunk.path("sectionHeader").asText("");
            if (looksLikeAttachedBody(sectionHeader)) {
                warnings.add("sectionHeader may contain attached body at chunk line " + (i + 1) + ": "
                        + abbreviate(sectionHeader));
            }
            Integer pageFrom = nullableInt(chunk, "pageFrom");
            Integer pageTo = nullableInt(chunk, "pageTo");
            if (isPdf(document) && (pageFrom == null || pageTo == null)) {
                warnings.add("PDF chunk line " + (i + 1) + " missing pageFrom/pageTo");
            }
        }
    }

    private void validateGeneratedChunks(Document document,
                                         List<HierarchicalMarkdownChunk> chunks,
                                         List<String> warnings) {
        for (int i = 0; i < chunks.size(); i++) {
            HierarchicalMarkdownChunk chunk = chunks.get(i);
            if (chunk.content() != null && chunk.content().length() > MAX_CHUNK_LENGTH) {
                warnings.add("chunk " + (i + 1) + " length " + chunk.content().length()
                        + " exceeds " + MAX_CHUNK_LENGTH);
            }
            String breadcrumbText = String.join(" > ", chunk.breadcrumb());
            if (looksLikeBodySentence(breadcrumbText)) {
                warnings.add("breadcrumb may start with body sentence at chunk " + (i + 1) + ": "
                        + abbreviate(breadcrumbText));
            }
            if (isPdf(document) && (chunk.pageFrom() == null || chunk.pageTo() == null)) {
                warnings.add("PDF generated chunk " + (i + 1) + " missing pageFrom/pageTo");
            }
        }
    }

    private boolean isPdf(Document document) {
        return document != null && "PDF".equalsIgnoreCase(document.getFileType());
    }

    private Integer nullableInt(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isNumber() ? value.asInt() : null;
    }

    private boolean looksLikeAttachedBody(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.length() > LONG_HEADING_THRESHOLD
                || trimmed.matches(".*[.!?]\\s+[A-ZÀ-Ỵ].*")
                || trimmed.matches("(?iu).+\\b(từ|trong|theo|hiện thực|khả năng|hoạt động|xã hội)\\b.+[.!?].*");
    }

    private boolean looksLikeBodySentence(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return trimmed.length() > LONG_HEADING_THRESHOLD
                || lower.startsWith("từ đầu ")
                || lower.startsWith("trong quá trình ")
                || lower.startsWith("theo quan điểm ")
                || lower.startsWith("hiện thực ");
    }

    private String abbreviate(String value) {
        if (value == null || value.length() <= 140) {
            return value;
        }
        return value.substring(0, 137) + "...";
    }

    public record ValidationReport(
            int errorCount,
            int warningCount,
            List<String> errors,
            List<String> warnings
    ) {
    }
}
