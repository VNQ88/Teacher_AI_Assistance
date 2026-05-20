package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.service.quiz.QuizInputMode;
import com.example.teacherassistantai.service.quiz.ReviewQuestionCoverage;
import com.example.teacherassistantai.service.quiz.ReviewQuestionGenerationContext;
import com.example.teacherassistantai.service.quiz.ReviewQuestionSourceMode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class DocumentEnrichmentArtifactValidationService {

    private static final Set<String> QUESTION_TYPES = Set.of("MULTIPLE_CHOICE", "TRUE_FALSE", "FILL_BLANK");
    private static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");
    private static final int ENOUGH_CONTEXT_CHARS = 1_200;

    private final ObjectMapper objectMapper;

    public DocumentEnrichmentArtifactValidationService() {
        this(new ObjectMapper());
    }

    DocumentEnrichmentArtifactValidationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> parseAndValidate(DocumentNodeArtifactType artifactType,
                                                String rawResponse,
                                                DocumentNode node,
                                                List<DocumentChunk> scopeChunks,
                                                int minQuestionCount,
                                                int maxQuestionCount) {
        JsonNode root = normalizeArtifactJson(artifactType, readJsonObject(rawResponse));
        SummaryCoverage expectedSummaryCoverage = null;
        SummaryMode expectedSummaryMode = null;
        if (artifactType == DocumentNodeArtifactType.SUMMARY) {
            expectedSummaryCoverage = SummaryCoverage.chunksOnly(safeSize(scopeChunks), safeSize(scopeChunks));
            expectedSummaryMode = chunksFallbackMode(node);
            validateSummary(
                    root,
                    scopeChunks,
                    List.of(),
                    expectedSummaryCoverage,
                    expectedSummaryMode
            );
        } else if (artifactType == DocumentNodeArtifactType.REVIEW_QUESTION_SET) {
            validateReviewQuestions(root, scopeChunks, minQuestionCount, maxQuestionCount);
        } else {
            throw new InvalidDataException("Unsupported enrichment artifact type: " + artifactType);
        }
        Map<String, Object> content = toMap(root);
        if (artifactType == DocumentNodeArtifactType.SUMMARY) {
            normalizeSummaryContent(content, node, scopeChunks, List.of(), expectedSummaryCoverage, expectedSummaryMode);
        }
        normalizeReviewQuestionCount(artifactType, content);
        content.putIfAbsent("nodeTitle", node == null ? null : node.getTitle());
        content.putIfAbsent("sectionPath", node == null ? null : node.getSectionPath());
        content.putIfAbsent("nodeType", node == null ? null : node.getNodeType());
        if (artifactType == DocumentNodeArtifactType.REVIEW_QUESTION_SET) {
            content.put("requestedQuestionMinCount", minQuestionCount);
            content.put("requestedQuestionMaxCount", maxQuestionCount);
        }
        content.put("generated", true);
        return content;
    }

    public Map<String, Object> parseAndValidateSummary(String rawResponse,
                                                       DocumentNode node,
                                                       List<DocumentChunk> directChunks,
                                                       List<ChildSummary> childSummaries,
                                                       SummaryCoverage expectedCoverage,
                                                       SummaryMode expectedMode) {
        JsonNode root = normalizeArtifactJson(DocumentNodeArtifactType.SUMMARY, readJsonObject(rawResponse));
        validateSummary(root, directChunks, childSummaries, expectedCoverage, expectedMode);
        Map<String, Object> content = toMap(root);
        normalizeSummaryContent(content, node, directChunks, childSummaries, expectedCoverage, expectedMode);
        content.put("generated", true);
        return content;
    }

    public Map<String, Object> parseAndValidateReviewQuestions(String rawResponse,
                                                               ReviewQuestionGenerationContext context) {
        JsonNode root = normalizeArtifactJson(DocumentNodeArtifactType.REVIEW_QUESTION_SET, readJsonObject(rawResponse));
        validateReviewQuestions(root, context);
        Map<String, Object> content = toMap(root);
        normalizeReviewQuestionCount(DocumentNodeArtifactType.REVIEW_QUESTION_SET, content);
        content.putIfAbsent("nodeTitle", context.node().getTitle());
        content.putIfAbsent("sectionPath", context.node().getSectionPath());
        content.putIfAbsent("nodeType", context.node().getNodeType());
        content.put("inputMode", context.inputMode().name());
        content.put("coverage", reviewCoverageMetadata(context.coverage()));
        content.put("childSummaryRefs", childSummaryRefs(context.childSummaries()));
        content.put("summaryBasedTargetCount", context.summaryBasedTargetCount());
        content.put("representativeTargetCount", context.representativeTargetCount());
        content.put("requestedQuestionMinCount", context.minQuestionCount());
        content.put("requestedQuestionMaxCount", context.maxQuestionCount());
        content.put("generated", true);
        return content;
    }

    private JsonNode normalizeArtifactJson(DocumentNodeArtifactType artifactType, JsonNode root) {
        if (!(root instanceof ObjectNode objectNode)) {
            return root;
        }
        if (artifactType == DocumentNodeArtifactType.SUMMARY) {
            copyTextAlias(objectNode, "summary", "summaryText", "shortSummary", "content", "text", "answer");
        } else if (artifactType == DocumentNodeArtifactType.REVIEW_QUESTION_SET) {
            copyQuestionsAlias(objectNode);
        }
        return objectNode;
    }

    private void copyTextAlias(ObjectNode objectNode, String targetField, String... aliases) {
        if (StringUtils.hasText(objectNode.path(targetField).asText(null))) {
            return;
        }
        for (String alias : aliases) {
            JsonNode value = objectNode.get(alias);
            if (value != null && value.isTextual() && StringUtils.hasText(value.asText())) {
                objectNode.set(targetField, value);
                return;
            }
        }
    }

    private void copyQuestionsAlias(ObjectNode objectNode) {
        if (objectNode.path("questions").isArray() && !objectNode.path("questions").isEmpty()) {
            return;
        }
        for (String alias : List.of("reviewQuestions", "questionSet", "items")) {
            JsonNode value = objectNode.get(alias);
            if (value != null && value.isArray() && !value.isEmpty()) {
                objectNode.set("questions", value);
                return;
            }
            if (value != null && value.isObject() && value.path("questions").isArray() && !value.path("questions").isEmpty()) {
                objectNode.set("questions", value.path("questions"));
                return;
            }
        }
    }

    private JsonNode readJsonObject(String rawResponse) {
        if (!StringUtils.hasText(rawResponse)) {
            throw new InvalidDataException("LLM response is empty");
        }
        String json = extractJsonObject(rawResponse);
        try {
            JsonNode node = objectMapper.readTree(escapeRawControlCharsInsideStrings(json));
            if (!node.isObject()) {
                throw new InvalidDataException("LLM response must be a JSON object");
            }
            return node;
        } catch (IOException ex) {
            throw new InvalidDataException("LLM response is not valid JSON: " + ex.getMessage(), ex);
        }
    }

    private String extractJsonObject(String rawResponse) {
        String value = rawResponse.trim();
        if (value.startsWith("```")) {
            int firstLineBreak = value.indexOf('\n');
            int closingFence = value.lastIndexOf("```");
            if (firstLineBreak >= 0 && closingFence > firstLineBreak) {
                value = value.substring(firstLineBreak + 1, closingFence).trim();
            }
        }

        int start = value.indexOf('{');
        if (start < 0) {
            throw new InvalidDataException("LLM response does not contain a JSON object");
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < value.length(); i++) {
            char current = value.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return value.substring(start, i + 1);
                }
            }
        }
        throw new InvalidDataException("LLM response does not contain a balanced JSON object");
    }

    private String escapeRawControlCharsInsideStrings(String json) {
        StringBuilder output = new StringBuilder(json.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char current = json.charAt(i);
            if (escaped) {
                output.append(current);
                escaped = false;
                continue;
            }
            if (current == '\\' && inString) {
                output.append(current);
                escaped = true;
                continue;
            }
            if (current == '"') {
                output.append(current);
                inString = !inString;
                continue;
            }
            if (inString && current == '\n') {
                output.append("\\n");
            } else if (inString && current == '\r') {
                output.append("\\n");
            } else if (inString && current == '\t') {
                output.append("\\t");
            } else {
                output.append(current);
            }
        }
        return output.toString();
    }

    private void validateSummary(JsonNode root,
                                 List<DocumentChunk> directChunks,
                                 List<ChildSummary> childSummaries,
                                 SummaryCoverage expectedCoverage,
                                 SummaryMode expectedMode) {
        requireText(root, "summary", "Summary is required");
        validateSummaryMode(root, expectedMode);
        validateExpectedSummaryCoverage(expectedCoverage);
        validateSummaryCitations(root.path("citations"), validChunkIds(directChunks), "summary citations");
    }

    private void validateReviewQuestions(JsonNode root,
                                         List<DocumentChunk> scopeChunks,
                                         int minQuestionCount,
                                         int maxQuestionCount) {
        JsonNode questions = root.path("questions");
        if (!questions.isArray() || questions.isEmpty()) {
            throw new InvalidDataException("Review question set must contain questions array");
        }

        int actualCount = questions.size();
        if (hasEnoughContext(scopeChunks) && (actualCount < minQuestionCount || actualCount > maxQuestionCount)) {
            throw new InvalidDataException("Question count must be between %d and %d".formatted(minQuestionCount, maxQuestionCount));
        }
        if (actualCount > maxQuestionCount) {
            throw new InvalidDataException("Question count exceeds max " + maxQuestionCount);
        }

        Set<Long> validChunkIds = validChunkIds(scopeChunks);
        for (int i = 0; i < questions.size(); i++) {
            validateQuestion(questions.get(i), validChunkIds, i + 1, false);
        }
    }

    private void validateReviewQuestions(JsonNode root, ReviewQuestionGenerationContext context) {
        JsonNode questions = root.path("questions");
        if (!questions.isArray() || questions.isEmpty()) {
            throw new InvalidDataException("Review question set must contain questions array");
        }

        int actualCount = questions.size();
        if (hasEnoughContext(context) && (actualCount < context.minQuestionCount() || actualCount > context.maxQuestionCount())) {
            throw new InvalidDataException("Question count must be between %d and %d"
                    .formatted(context.minQuestionCount(), context.maxQuestionCount()));
        }
        if (actualCount > context.maxQuestionCount()) {
            throw new InvalidDataException("Question count exceeds max " + context.maxQuestionCount());
        }

        boolean requireSourceMode = context.inputMode() == QuizInputMode.MIXED_CHILD_SUMMARIES_AND_REPRESENTATIVE_CHUNKS;
        Set<Long> validChunkIds = validChunkIds(context.allowedCitationChunks());
        for (int i = 0; i < questions.size(); i++) {
            validateQuestion(questions.get(i), validChunkIds, i + 1, requireSourceMode);
        }
    }

    private void validateQuestion(JsonNode question,
                                  Set<Long> validChunkIds,
                                  int questionNumber,
                                  boolean requireSourceMode) {
        String prefix = "Question " + questionNumber + ": ";
        String sourceMode = optionalText(question, "sourceMode");
        if (requireSourceMode && !StringUtils.hasText(sourceMode)) {
            throw new InvalidDataException(prefix + "sourceMode is required");
        }
        if (StringUtils.hasText(sourceMode)) {
            try {
                ReviewQuestionSourceMode.valueOf(sourceMode.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new InvalidDataException(prefix + "unsupported sourceMode " + sourceMode, ex);
            }
        }
        String type = requireText(question, "type", prefix + "type is required").toUpperCase(Locale.ROOT);
        if (!QUESTION_TYPES.contains(type)) {
            throw new InvalidDataException(prefix + "unsupported type " + type);
        }
        requireText(question, "question", prefix + "question text is required");
        requireText(question, "answerExplanation", prefix + "answerExplanation is required");
        String difficulty = optionalText(question, "difficulty");
        if (difficulty != null && !DIFFICULTIES.contains(difficulty.toUpperCase(Locale.ROOT))) {
            throw new InvalidDataException(prefix + "unsupported difficulty " + difficulty);
        }
        validateCitations(question.path("citations"), validChunkIds, prefix + "citations");

        if ("MULTIPLE_CHOICE".equals(type)) {
            validateMultipleChoice(question, prefix);
        } else if ("TRUE_FALSE".equals(type)) {
            if (!question.path("correctAnswer").isBoolean()) {
                throw new InvalidDataException(prefix + "TRUE_FALSE correctAnswer must be boolean");
            }
        } else {
            requireText(question, "correctAnswer", prefix + "FILL_BLANK correctAnswer is required");
        }
    }

    private void validateMultipleChoice(JsonNode question, String prefix) {
        JsonNode options = question.path("options");
        if (!options.isArray() || options.size() < 2) {
            throw new InvalidDataException(prefix + "MULTIPLE_CHOICE options must contain at least 2 options");
        }
        Set<String> labels = new HashSet<>();
        for (JsonNode option : options) {
            String label = requireText(option, "label", prefix + "option label is required");
            requireText(option, "content", prefix + "option content is required");
            labels.add(label);
        }
        String correctAnswer = requireText(question, "correctAnswer", prefix + "MULTIPLE_CHOICE correctAnswer is required");
        if (correctAnswer.length() <= 3 && !labels.contains(correctAnswer)) {
            throw new InvalidDataException(prefix + "correctAnswer must match an option label");
        }
    }

    private void validateCitations(JsonNode citations, Set<Long> validChunkIds, String fieldName) {
        if (!citations.isArray() || citations.isEmpty()) {
            if (validChunkIds.isEmpty()) {
                return;
            }
            throw new InvalidDataException(fieldName + " must contain at least one citation");
        }
        for (JsonNode citation : citations) {
            JsonNode chunkIdNode = citation.path("chunkId");
            if (!chunkIdNode.isIntegralNumber()) {
                throw new InvalidDataException(fieldName + " citation chunkId is required");
            }
            Long chunkId = chunkIdNode.asLong();
            if (!validChunkIds.contains(chunkId)) {
                throw new InvalidDataException(fieldName + " contains chunkId outside scope: " + chunkId);
            }
        }
    }

    private void validateSummaryCitations(JsonNode citations, Set<Long> validChunkIds, String fieldName) {
        if (validChunkIds.isEmpty()) {
            return;
        }
        if (!citations.isArray() || citations.isEmpty()) {
            throw new InvalidDataException(fieldName + " must contain at least one citation");
        }
        for (JsonNode citation : citations) {
            JsonNode chunkIdNode = citation.path("chunkId");
            if (!chunkIdNode.isIntegralNumber()) {
                throw new InvalidDataException(fieldName + " citation chunkId is required");
            }
            Long chunkId = chunkIdNode.asLong();
            if (!validChunkIds.contains(chunkId)) {
                throw new InvalidDataException(fieldName + " contains chunkId outside direct chunk scope: " + chunkId);
            }
        }
    }

    private void validateSummaryMode(JsonNode root, SummaryMode expectedMode) {
        String rawMode = requireText(root, "summaryMode", "summaryMode is required");
        SummaryMode actualMode;
        try {
            actualMode = SummaryMode.valueOf(rawMode);
        } catch (IllegalArgumentException ex) {
            throw new InvalidDataException("Unsupported summaryMode: " + rawMode, ex);
        }
        if (expectedMode != null && actualMode != expectedMode) {
            throw new InvalidDataException("summaryMode must be " + expectedMode);
        }
    }

    private void normalizeReviewQuestionCount(DocumentNodeArtifactType artifactType, Map<String, Object> content) {
        if (artifactType != DocumentNodeArtifactType.REVIEW_QUESTION_SET) {
            return;
        }
        Object questions = content.get("questions");
        if (questions instanceof List<?> questionList) {
            content.put("questionCount", questionList.size());
        }
    }

    private Set<Long> validChunkIds(List<DocumentChunk> scopeChunks) {
        Set<Long> validChunkIds = new HashSet<>();
        for (DocumentChunk chunk : scopeChunks == null ? List.<DocumentChunk>of() : scopeChunks) {
            if (chunk != null && chunk.getId() != null) {
                validChunkIds.add(chunk.getId());
            }
        }
        return validChunkIds;
    }

    private boolean hasEnoughContext(List<DocumentChunk> scopeChunks) {
        int chars = 0;
        for (DocumentChunk chunk : scopeChunks == null ? List.<DocumentChunk>of() : scopeChunks) {
            if (chunk != null && chunk.getContent() != null) {
                chars += chunk.getContent().length();
            }
        }
        return chars >= ENOUGH_CONTEXT_CHARS;
    }

    private void normalizeSummaryContent(Map<String, Object> content,
                                         DocumentNode node,
                                         List<DocumentChunk> directChunks,
                                         List<ChildSummary> childSummaries,
                                         SummaryCoverage expectedCoverage,
                                         SummaryMode expectedMode) {
        content.put("nodeTitle", node == null ? null : node.getTitle());
        content.put("sectionPath", node == null ? null : node.getSectionPath());
        content.put("nodeType", node == null ? null : node.getNodeType());
        if (expectedMode != null) {
            content.put("summaryMode", expectedMode.name());
        }
        if (validChunkIds(directChunks).isEmpty()) {
            content.put("citations", List.of());
        }
        content.put("coverage", summaryCoverageMetadata(expectedCoverage));
        content.put("childSummaries", childSummariesMetadata(childSummaries));
        content.put("childSummaryRefs", childSummaryRefs(childSummaries));
    }

    private void validateExpectedSummaryCoverage(SummaryCoverage coverage) {
        if (coverage == null) {
            return;
        }
        if (!coverage.complete()) {
            throw new InvalidDataException("expected summary coverage must be complete");
        }
        if (coverage.usedChildCount() > coverage.expectedChildCount()) {
            throw new InvalidDataException("expected summary coverage usedChildCount cannot exceed expectedChildCount");
        }
        if (coverage.fallbackChildCount() < 0 || coverage.fallbackChildCount() > coverage.expectedChildCount()) {
            throw new InvalidDataException("expected summary coverage fallbackChildCount out of range");
        }
        if (coverage.expectedChildCount() > 0
                && coverage.usedChildCount() + coverage.fallbackChildCount() < coverage.expectedChildCount()) {
            throw new InvalidDataException("expected summary coverage is incomplete");
        }
        if (coverage.usedDirectChunkCount() > coverage.directChunkCount()) {
            throw new InvalidDataException("expected summary coverage usedDirectChunkCount cannot exceed directChunkCount");
        }
        if (coverage.missingChildNodeIds() != null && !coverage.missingChildNodeIds().isEmpty()) {
            throw new InvalidDataException("expected summary coverage missingChildNodeIds must be empty");
        }
    }

    private Map<String, Object> summaryCoverageMetadata(SummaryCoverage coverage) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("expectedChildCount", coverage == null ? 0 : coverage.expectedChildCount());
        metadata.put("usedChildCount", coverage == null ? 0 : coverage.usedChildCount());
        metadata.put("missingChildNodeIds", coverage == null ? List.of() : coverage.missingChildNodeIds());
        metadata.put("directChunkCount", coverage == null ? 0 : coverage.directChunkCount());
        metadata.put("usedDirectChunkCount", coverage == null ? 0 : coverage.usedDirectChunkCount());
        metadata.put("complete", coverage == null || coverage.complete());
        metadata.put("fallbackChildCount", coverage == null ? 0 : coverage.fallbackChildCount());
        return metadata;
    }

    private List<Map<String, Object>> childSummariesMetadata(List<ChildSummary> childSummaries) {
        return (childSummaries == null ? List.<ChildSummary>of() : childSummaries).stream()
                .map(summary -> {
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("nodeId", summary.nodeId());
                    metadata.put("nodeType", summary.nodeType());
                    metadata.put("title", summary.title());
                    metadata.put("sectionPath", summary.sectionPath());
                    metadata.put("summary", summary.summary());
                    metadata.put("citations", summary.citations() == null ? List.of() : summary.citations());
                    return metadata;
                })
                .toList();
    }

    private boolean hasEnoughContext(ReviewQuestionGenerationContext context) {
        int chars = 0;
        for (DocumentChunk chunk : context.allowedCitationChunks()) {
            if (chunk != null && chunk.getContent() != null) {
                chars += chunk.getContent().length();
            }
        }
        for (ChildSummary childSummary : context.childSummaries()) {
            if (childSummary != null && childSummary.summary() != null) {
                chars += childSummary.summary().length();
            }
        }
        return chars >= ENOUGH_CONTEXT_CHARS;
    }

    private Map<String, Object> reviewCoverageMetadata(ReviewQuestionCoverage coverage) {
        if (coverage == null) {
            return Map.of();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("expectedChildCount", coverage.expectedChildCount());
        metadata.put("usedChildSummaryCount", coverage.usedChildSummaryCount());
        metadata.put("fallbackChildCount", coverage.fallbackChildCount());
        metadata.put("representativeChildCount", coverage.representativeChildCount());
        metadata.put("rawChunkCount", coverage.rawChunkCount());
        metadata.put("allowedCitationChunkCount", coverage.allowedCitationChunkCount());
        metadata.put("complete", coverage.complete());
        return metadata;
    }

    private List<Map<String, Object>> childSummaryRefs(List<ChildSummary> childSummaries) {
        return (childSummaries == null ? List.<ChildSummary>of() : childSummaries).stream()
                .map(summary -> {
                    Map<String, Object> ref = new LinkedHashMap<>();
                    ref.put("nodeId", summary.nodeId());
                    ref.put("artifactId", summary.artifactId());
                    ref.put("sourceHash", summary.sourceHash());
                    return ref;
                })
                .toList();
    }

    private int safeSize(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private SummaryMode chunksFallbackMode(DocumentNode node) {
        String nodeType = node == null ? "" : node.getNodeType();
        return switch (nodeType) {
            case "subsection_level2" -> SummaryMode.SUBSECTION_LEVEL2_FROM_CHUNKS;
            case "subsection" -> SummaryMode.SUBSECTION_FROM_CHUNKS;
            case "section" -> SummaryMode.SECTION_FROM_CHUNKS_FALLBACK;
            case "part" -> SummaryMode.PART_FALLBACK;
            default -> SummaryMode.CHAPTER_FALLBACK;
        };
    }

    private String requireText(JsonNode node, String fieldName, String message) {
        String value = optionalText(node, fieldName);
        if (!StringUtils.hasText(value)) {
            throw new InvalidDataException(message);
        }
        return value;
    }

    private String optionalText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isTextual() ? value.asText() : null;
    }

    private Map<String, Object> toMap(JsonNode root) {
        try {
            return objectMapper.readValue(objectMapper.writeValueAsBytes(root), new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new InvalidDataException("Failed to convert validated enrichment JSON", ex);
        } catch (IOException ex) {
            throw new InvalidDataException("Failed to convert validated enrichment JSON", ex);
        }
    }
}
