package com.example.teacherassistantai.eval;

import com.example.teacherassistantai.TeacherAssistantAiApplication;
import com.example.teacherassistantai.common.enumerate.ChatSessionType;
import com.example.teacherassistantai.dto.response.RagDebugRetrieveResponse;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import com.example.teacherassistantai.service.VectorRetrievalService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalEvalSnapshotTest {

    private static final DateTimeFormatter RUN_ID_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String DEFAULT_GOLDEN_FILE = "src/test/resources/eval/retrieval/golden-questions.jsonl";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setDefaultPropertyInclusion(JsonInclude.Value.construct(
                    JsonInclude.Include.NON_NULL,
                    JsonInclude.Include.NON_NULL
            ));

    @Test
    void generateRetrievalEvalSnapshot() throws Exception {
        Assumptions.assumeTrue(isEnabled(),
                "Skip retrieval eval snapshot. Enable with -Dretrieval.eval.run=true");

        String runId = resolveRunId();
        Path runDir = Path.of("output", "retrieval-eval", runId);
        Files.createDirectories(runDir.resolve("catalog"));

        List<GoldenQuestion> questions = loadGoldenQuestions(goldenFile());
        List<Map<String, Object>> questionResults = new ArrayList<>();

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TeacherAssistantAiApplication.class)
                .web(WebApplicationType.NONE)
                .properties(Map.of(
                        "spring.main.web-application-type", "none",
                        "spring.main.banner-mode", "off"
                ))
                .run()) {

            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            VectorRetrievalService retrievalService = context.getBean(VectorRetrievalService.class);
            DocumentRepository documentRepository = context.getBean(DocumentRepository.class);
            DocumentNodeRepository nodeRepository = context.getBean(DocumentNodeRepository.class);
            DocumentChunkRepository chunkRepository = context.getBean(DocumentChunkRepository.class);

            tx.executeWithoutResult(ignored -> writeCatalog(runDir.resolve("catalog"),
                    documentRepository, nodeRepository, chunkRepository));

            for (GoldenQuestion question : questions) {
                if (Boolean.TRUE.equals(question.disabled())) {
                    questionResults.add(skippedResult(question, "disabled"));
                    continue;
                }

                Optional<Subject> subject = tx.execute(status ->
                        resolveSubjectForQuestion(documentRepository.findAll(), question));
                if (subject == null || subject.isEmpty()) {
                    questionResults.add(skippedResult(question, "expected document is not present in database"));
                    continue;
                }

                ChatSession session = ChatSession.builder()
                        .subject(subject.get())
                        .sessionType(ChatSessionType.KNOWLEDGE_QA)
                        .active(true)
                        .build();

                long startedAt = System.currentTimeMillis();
                try {
                    RagDebugRetrieveResponse response = tx.execute(status -> retrievalService.debugRetrieve(
                            session,
                            question.question(),
                            question.topK() == null ? 6 : question.topK()
                    ));
                    long latencyMs = System.currentTimeMillis() - startedAt;
                    questionResults.add(evaluate(question, response, latencyMs, null));
                } catch (Exception ex) {
                    long latencyMs = System.currentTimeMillis() - startedAt;
                    questionResults.add(evaluate(question, null, latencyMs, ex));
                }
            }
        }

        Map<String, Object> report = buildReport(runId, goldenFile(), questionResults);
        List<Map<String, Object>> failures = questionResults.stream()
                .filter(result -> "FAILED".equals(result.get("status")) || "ERROR".equals(result.get("status")))
                .toList();

        Files.writeString(runDir.resolve("per-question-results.jsonl"), toJsonl(questionResults), StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("failures.jsonl"), toJsonl(failures), StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("report.json"), objectMapper.writeValueAsString(report), StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("report.md"), renderMarkdownReport(report, failures), StandardCharsets.UTF_8);

        if (Boolean.getBoolean("retrieval.eval.failOnMiss")) {
            assertThat(failures)
                    .describedAs("retrieval eval misses; inspect " + runDir.resolve("failures.jsonl"))
                    .isEmpty();
        }
    }

    private void writeCatalog(Path catalogDir,
                              DocumentRepository documentRepository,
                              DocumentNodeRepository nodeRepository,
                              DocumentChunkRepository chunkRepository) {
        try {
            List<Document> documents = documentRepository.findAll().stream()
                    .sorted(Comparator.comparing(Document::getId))
                    .toList();
            List<Map<String, Object>> documentRows = documents.stream()
                    .map(this::documentCatalogRow)
                    .toList();
            Files.writeString(catalogDir.resolve("documents.json"),
                    objectMapper.writeValueAsString(documentRows), StandardCharsets.UTF_8);

            List<Map<String, Object>> nodeRows = new ArrayList<>();
            StringBuilder chunksJsonl = new StringBuilder();
            for (Document document : documents) {
                nodeRepository.findByDocumentIdOrderByOrderIndexAsc(document.getId()).stream()
                        .map(this::nodeCatalogRow)
                        .forEach(nodeRows::add);
                for (DocumentChunk chunk : chunkRepository.findByDocumentIdOrderByChunkIndexAsc(document.getId())) {
                    chunksJsonl.append(objectMapper.writeValueAsString(chunkCatalogRow(chunk))).append('\n');
                }
            }
            Files.writeString(catalogDir.resolve("nodes.json"),
                    objectMapper.writeValueAsString(nodeRows), StandardCharsets.UTF_8);
            Files.writeString(catalogDir.resolve("chunks.jsonl"), chunksJsonl.toString(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to write retrieval eval catalog", ex);
        }
    }

    private Map<String, Object> documentCatalogRow(Document document) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("documentId", document.getId());
        row.put("title", document.getTitle());
        row.put("status", document.getStatus() == null ? null : document.getStatus().name());
        row.put("subjectId", document.getSubject() == null ? null : document.getSubject().getId());
        row.put("subjectName", document.getSubject() == null ? null : document.getSubject().getName());
        row.put("fileType", document.getFileType());
        return row;
    }

    private Map<String, Object> nodeCatalogRow(DocumentNode node) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nodeId", node.getId());
        row.put("documentId", node.getDocument() == null ? null : node.getDocument().getId());
        row.put("nodeKey", node.getNodeKey());
        row.put("parentId", node.getParent() == null ? null : node.getParent().getId());
        row.put("nodeType", node.getNodeType());
        row.put("level", node.getLevel());
        row.put("title", node.getTitle());
        row.put("sectionPath", node.getSectionPath());
        row.put("orderIndex", node.getOrderIndex());
        row.put("pageFrom", node.getPageFrom());
        row.put("pageTo", node.getPageTo());
        return row;
    }

    private Map<String, Object> chunkCatalogRow(DocumentChunk chunk) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("chunkId", chunk.getId());
        row.put("documentId", chunk.getDocument() == null ? null : chunk.getDocument().getId());
        row.put("chunkIndex", chunk.getChunkIndex());
        row.put("sourceOrder", chunk.getSourceOrder());
        row.put("chunkType", chunk.getChunkType());
        row.put("nodeId", chunk.getNode() == null ? null : chunk.getNode().getId());
        row.put("parentNodeId", chunk.getParentNode() == null ? null : chunk.getParentNode().getId());
        row.put("sectionPath", chunk.getSectionPath());
        row.put("pageFrom", chunk.getPageFrom());
        row.put("pageTo", chunk.getPageTo());
        row.put("snippet", snippet(chunk.getContent()));
        return row;
    }

    private Optional<Subject> resolveSubjectForQuestion(List<Document> documents, GoldenQuestion question) {
        List<String> expectedDocuments = expectedDocuments(question);
        return documents.stream()
                .filter(document -> document.getSubject() != null)
                .filter(document -> expectedDocuments.isEmpty()
                        || expectedDocuments.stream().anyMatch(expected -> relaxedContains(document.getTitle(), expected)))
                .findFirst()
                .map(Document::getSubject);
    }

    private Map<String, Object> evaluate(GoldenQuestion question,
                                         RagDebugRetrieveResponse response,
                                         long latencyMs,
                                         Exception error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", question.id());
        result.put("question", question.question());
        result.put("tags", safeList(question.tags()));
        result.put("expectedDocuments", expectedDocuments(question));
        result.put("expectedCoarseSectionPaths", safeList(question.expectedCoarseSectionPaths()));
        result.put("expectedSectionPaths", safeList(question.expectedSectionPaths()));
        result.put("expectedSnippets", safeList(question.expectedSnippets()));
        result.put("latencyMs", latencyMs);
        boolean hasCoarseExpectation = !safeList(question.expectedCoarseSectionPaths()).isEmpty();
        result.put("coarseEvaluated", hasCoarseExpectation);

        if (error != null) {
            result.put("status", "ERROR");
            result.put("error", error.getClass().getSimpleName() + ": " + error.getMessage());
            return result;
        }
        if (response == null) {
            result.put("status", "ERROR");
            result.put("error", "empty retrieval response");
            return result;
        }

        List<RagDebugRetrieveResponse.Chunk> candidates = safeChunks(response.getCandidateChunks());
        List<RagDebugRetrieveResponse.Chunk> selected = safeChunks(response.getSelectedChunks());
        boolean candidateDocumentHit = containsMatchingChunk(candidates, chunk -> documentHit(question, chunk));
        boolean selectedDocumentHit = containsMatchingChunk(selected, chunk -> documentHit(question, chunk));
        boolean candidatePathHit = containsMatchingChunk(candidates, chunk -> pathHit(question, chunk));
        boolean selectedPathHit = containsMatchingChunk(selected, chunk -> pathHit(question, chunk));
        boolean candidateSnippetHit = containsMatchingChunk(candidates, chunk -> snippetHit(question, chunk));
        boolean selectedSnippetHit = containsMatchingChunk(selected, chunk -> snippetHit(question, chunk));
        boolean candidateCoarsePathHit = hasCoarseExpectation
                && containsMatchingChunk(candidates, chunk -> coarsePathHit(question, chunk));
        boolean selectedCoarsePathHit = hasCoarseExpectation
                && containsMatchingChunk(selected, chunk -> coarsePathHit(question, chunk));

        boolean hasEvidenceExpectation = !safeList(question.expectedSectionPaths()).isEmpty()
                || !safeList(question.expectedSnippets()).isEmpty();
        boolean selectedEvidenceHit = hasEvidenceExpectation
                ? selectedPathHit || selectedSnippetHit
                : selectedDocumentHit;
        boolean candidateEvidenceHit = hasEvidenceExpectation
                ? candidatePathHit || candidateSnippetHit
                : candidateDocumentHit;

        result.put("status", selectedEvidenceHit ? "PASSED" : "FAILED");
        result.put("candidateEvidenceHit", candidateEvidenceHit);
        result.put("selectedEvidenceHit", selectedEvidenceHit);
        result.put("candidateDocumentHit", candidateDocumentHit);
        result.put("selectedDocumentHit", selectedDocumentHit);
        result.put("candidatePathHit", candidatePathHit);
        result.put("selectedPathHit", selectedPathHit);
        result.put("candidateSnippetHit", candidateSnippetHit);
        result.put("selectedSnippetHit", selectedSnippetHit);
        result.put("candidateCoarsePathHit", candidateCoarsePathHit);
        result.put("selectedCoarsePathHit", selectedCoarsePathHit);
        result.put("candidateEvidenceMrr", reciprocalRank(candidates, chunk -> evidenceHit(question, chunk)));
        result.put("selectedEvidenceMrr", reciprocalRank(selected, chunk -> evidenceHit(question, chunk)));
        result.put("candidateCoarseMrr", hasCoarseExpectation
                ? reciprocalRank(candidates, chunk -> coarsePathHit(question, chunk))
                : null);
        result.put("selectedCoarseMrr", hasCoarseExpectation
                ? reciprocalRank(selected, chunk -> coarsePathHit(question, chunk))
                : null);
        result.put("retrievalMode", response.getRetrievalMode());
        result.put("intentType", response.getIntentType());
        result.put("sectionNumber", response.getSectionNumber());
        result.put("candidateCount", response.getCandidateCount());
        result.put("selectedCount", response.getSelectedCount());
        result.put("selectedChunks", selected);
        result.put("candidateChunks", candidates);
        return result;
    }

    private boolean evidenceHit(GoldenQuestion question, RagDebugRetrieveResponse.Chunk chunk) {
        return pathHit(question, chunk) || snippetHit(question, chunk) || documentHit(question, chunk);
    }

    private boolean documentHit(GoldenQuestion question, RagDebugRetrieveResponse.Chunk chunk) {
        List<String> expectedDocuments = expectedDocuments(question);
        return expectedDocuments.isEmpty()
                || expectedDocuments.stream().anyMatch(expected -> relaxedContains(chunk.getDocumentTitle(), expected));
    }

    private boolean pathHit(GoldenQuestion question, RagDebugRetrieveResponse.Chunk chunk) {
        List<String> expectedPaths = safeList(question.expectedSectionPaths());
        return !expectedPaths.isEmpty()
                && expectedPaths.stream().anyMatch(expected -> pathContains(chunk.getSectionPath(), expected));
    }

    private boolean coarsePathHit(GoldenQuestion question, RagDebugRetrieveResponse.Chunk chunk) {
        List<String> expectedPaths = safeList(question.expectedCoarseSectionPaths());
        return !expectedPaths.isEmpty()
                && expectedPaths.stream().anyMatch(expected -> pathContains(chunk.getSectionPath(), expected));
    }

    private boolean snippetHit(GoldenQuestion question, RagDebugRetrieveResponse.Chunk chunk) {
        List<String> expectedSnippets = safeList(question.expectedSnippets());
        return !expectedSnippets.isEmpty()
                && expectedSnippets.stream().anyMatch(expected -> relaxedContains(chunk.getSnippet(), expected));
    }

    private double reciprocalRank(List<RagDebugRetrieveResponse.Chunk> chunks,
                                  Predicate<RagDebugRetrieveResponse.Chunk> predicate) {
        for (int i = 0; i < chunks.size(); i++) {
            if (predicate.test(chunks.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private boolean containsMatchingChunk(List<RagDebugRetrieveResponse.Chunk> chunks,
                                          Predicate<RagDebugRetrieveResponse.Chunk> predicate) {
        return chunks.stream().anyMatch(predicate);
    }

    private Map<String, Object> skippedResult(GoldenQuestion question, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", question.id());
        result.put("question", question.question());
        result.put("tags", safeList(question.tags()));
        result.put("status", "SKIPPED");
        result.put("skipReason", reason);
        return result;
    }

    private Map<String, Object> buildReport(String runId,
                                            Path goldenFile,
                                            List<Map<String, Object>> questionResults) {
        List<Map<String, Object>> answered = questionResults.stream()
                .filter(result -> !"SKIPPED".equals(result.get("status")))
                .toList();
        long passed = count(answered, "PASSED");
        long failed = count(answered, "FAILED");
        long errored = count(answered, "ERROR");
        long skipped = count(questionResults, "SKIPPED");
        List<Map<String, Object>> coarseAnswered = answered.stream()
                .filter(result -> Boolean.TRUE.equals(result.get("coarseEvaluated")))
                .toList();
        Map<String, Long> retrievalModes = new LinkedHashMap<>();
        Map<String, Long> tagCounts = new LinkedHashMap<>();
        for (Map<String, Object> result : answered) {
            Object mode = result.get("retrievalMode");
            if (mode != null) {
                retrievalModes.merge(String.valueOf(mode), 1L, Long::sum);
            }
            tags(result).forEach(tag -> tagCounts.merge(tag, 1L, Long::sum));
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("passed", passed);
        metrics.put("failed", failed);
        metrics.put("errored", errored);
        metrics.put("skipped", skipped);
        metrics.put("passRate", ratio(passed, answered.size()));
        metrics.put("candidateEvidenceHitRate", booleanRate(answered, "candidateEvidenceHit"));
        metrics.put("selectedEvidenceHitRate", booleanRate(answered, "selectedEvidenceHit"));
        metrics.put("candidatePathHitRate", booleanRate(answered, "candidatePathHit"));
        metrics.put("selectedPathHitRate", booleanRate(answered, "selectedPathHit"));
        metrics.put("candidateSnippetHitRate", booleanRate(answered, "candidateSnippetHit"));
        metrics.put("selectedSnippetHitRate", booleanRate(answered, "selectedSnippetHit"));
        metrics.put("coarseEvaluated", coarseAnswered.size());
        metrics.put("candidateCoarsePathHitRate", booleanRate(coarseAnswered, "candidateCoarsePathHit"));
        metrics.put("selectedCoarsePathHitRate", booleanRate(coarseAnswered, "selectedCoarsePathHit"));
        metrics.put("avgCandidateEvidenceMrr", averageDouble(answered, "candidateEvidenceMrr"));
        metrics.put("avgSelectedEvidenceMrr", averageDouble(answered, "selectedEvidenceMrr"));
        metrics.put("avgCandidateCoarseMrr", averageDouble(coarseAnswered, "candidateCoarseMrr"));
        metrics.put("avgSelectedCoarseMrr", averageDouble(coarseAnswered, "selectedCoarseMrr"));
        metrics.put("avgLatencyMs", averageLong(answered, "latencyMs"));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", runId);
        report.put("generatedAt", LocalDateTime.now().toString());
        report.put("goldenFile", goldenFile.toString());
        report.put("totalQuestions", questionResults.size());
        report.put("answeredQuestions", answered.size());
        report.put("retrievalModes", retrievalModes);
        report.put("tagCounts", tagCounts);
        report.put("metrics", metrics);
        return report;
    }

    private String renderMarkdownReport(Map<String, Object> report, List<Map<String, Object>> failures) {
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) report.get("metrics");
        StringBuilder builder = new StringBuilder();
        builder.append("# Retrieval Eval Report\n\n");
        builder.append("- runId: `").append(report.get("runId")).append("`\n");
        builder.append("- goldenFile: `").append(report.get("goldenFile")).append("`\n");
        builder.append("- totalQuestions: ").append(report.get("totalQuestions")).append('\n');
        builder.append("- answeredQuestions: ").append(report.get("answeredQuestions")).append("\n\n");
        builder.append("| metric | value |\n|---|---:|\n");
        for (Map.Entry<String, Object> entry : metrics.entrySet()) {
            builder.append('|').append(entry.getKey()).append('|').append(entry.getValue()).append("|\n");
        }
        appendCountTable(builder, "Retrieval Modes", report.get("retrievalModes"));
        appendCountTable(builder, "Tag Counts", report.get("tagCounts"));
        if (!failures.isEmpty()) {
            builder.append("\n## Failures\n\n");
            builder.append("| id | status | retrievalMode | question |\n|---|---|---|---|\n");
            for (Map<String, Object> failure : failures) {
                builder.append('|').append(failure.get("id"))
                        .append('|').append(failure.get("status"))
                        .append('|').append(failure.getOrDefault("retrievalMode", ""))
                        .append('|').append(escapeTable(String.valueOf(failure.get("question"))))
                        .append("|\n");
            }
        }
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private void appendCountTable(StringBuilder builder, String title, Object rawCounts) {
        if (!(rawCounts instanceof Map<?, ?> counts) || counts.isEmpty()) {
            return;
        }
        builder.append("\n## ").append(title).append("\n\n");
        builder.append("| value | count |\n|---|---:|\n");
        for (Map.Entry<?, ?> entry : counts.entrySet()) {
            builder.append('|')
                    .append(escapeTable(String.valueOf(entry.getKey())))
                    .append('|')
                    .append(entry.getValue())
                    .append("|\n");
        }
    }

    private long count(List<Map<String, Object>> results, String status) {
        return results.stream().filter(result -> status.equals(result.get("status"))).count();
    }

    private double booleanRate(List<Map<String, Object>> results, String field) {
        if (results.isEmpty()) return 0.0;
        long hits = results.stream().filter(result -> Boolean.TRUE.equals(result.get(field))).count();
        return ratio(hits, results.size());
    }

    private double averageDouble(List<Map<String, Object>> results, String field) {
        return results.stream()
                .map(result -> result.get(field))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .mapToDouble(Number::doubleValue)
                .average()
                .orElse(0.0);
    }

    private long averageLong(List<Map<String, Object>> results, String field) {
        return Math.round(results.stream()
                .map(result -> result.get(field))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .mapToLong(Number::longValue)
                .average()
                .orElse(0.0));
    }

    private double ratio(long numerator, long denominator) {
        if (denominator == 0) return 0.0;
        return Math.round(((double) numerator / denominator) * 10_000.0) / 10_000.0;
    }

    @SuppressWarnings("unchecked")
    private List<String> tags(Map<String, Object> result) {
        Object rawTags = result.get("tags");
        if (!(rawTags instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private List<GoldenQuestion> loadGoldenQuestions(Path path) throws Exception {
        List<GoldenQuestion> questions = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("#")) {
                continue;
            }
            questions.add(objectMapper.readValue(trimmed, GoldenQuestion.class));
        }
        return questions;
    }

    private String toJsonl(List<Map<String, Object>> values) throws Exception {
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> value : values) {
            builder.append(objectMapper.writeValueAsString(value).replace("\n", "")).append('\n');
        }
        return builder.toString();
    }

    private Path goldenFile() {
        return Path.of(System.getProperty("retrieval.eval.goldenFile", DEFAULT_GOLDEN_FILE));
    }

    private boolean isEnabled() {
        String systemValue = System.getProperty("retrieval.eval.run");
        if (systemValue != null && !systemValue.isBlank()) {
            return Boolean.parseBoolean(systemValue);
        }
        String envValue = System.getenv("RETRIEVAL_EVAL_RUN");
        return envValue != null && Boolean.parseBoolean(envValue);
    }

    private String resolveRunId() {
        String systemValue = System.getProperty("retrieval.eval.runId");
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue;
        }
        String envValue = System.getenv("RETRIEVAL_EVAL_RUN_ID");
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        return LocalDateTime.now().format(RUN_ID_FORMATTER);
    }

    private List<RagDebugRetrieveResponse.Chunk> safeChunks(List<RagDebugRetrieveResponse.Chunk> chunks) {
        return chunks == null ? List.of() : chunks;
    }

    private List<String> expectedDocuments(GoldenQuestion question) {
        return safeList(question.expectedDocuments());
    }

    private List<String> safeList(List<String> value) {
        return value == null ? List.of() : value.stream()
                .filter(Objects::nonNull)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private boolean pathContains(String actualPath, String expectedPath) {
        String normalizedActual = normalize(actualPath);
        List<String> expectedSegments = List.of(expectedPath.split(">")).stream()
                .map(this::normalize)
                .filter(segment -> !segment.isBlank())
                .toList();
        if (expectedSegments.isEmpty()) {
            return false;
        }
        return expectedSegments.stream().allMatch(normalizedActual::contains);
    }

    private boolean relaxedContains(String actual, String expected) {
        String normalizedActual = normalize(actual);
        String normalizedExpected = normalize(expected);
        return !normalizedExpected.isBlank()
                && !normalizedActual.isBlank()
                && (normalizedActual.contains(normalizedExpected) || normalizedExpected.contains(normalizedActual));
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd')
                .replace('Đ', 'D')
                .toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[^\\p{L}\\p{N}\\s>.-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String snippet(String content) {
        if (content == null || content.isBlank()) return "";
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 320 ? normalized : normalized.substring(0, 317) + "...";
    }

    private String escapeTable(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private record GoldenQuestion(
            String id,
            String question,
            List<String> expectedDocuments,
            List<String> expectedCoarseSectionPaths,
            List<String> expectedSectionPaths,
            List<String> expectedSnippets,
            List<String> tags,
            Integer topK,
            Boolean disabled,
            String notes
    ) {
    }
}
