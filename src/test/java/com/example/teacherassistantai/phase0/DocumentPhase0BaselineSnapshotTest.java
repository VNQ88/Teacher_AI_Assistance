package com.example.teacherassistantai.phase0;

import com.example.teacherassistantai.config.DocumentIngestionProps;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.integration.tika.PdfMarkdownPostProcessor;
import com.example.teacherassistantai.integration.tika.TikaMarkdownParser;
import com.example.teacherassistantai.service.DocumentHierarchyArtifactService;
import com.example.teacherassistantai.service.DocumentHierarchyArtifactValidationService;
import com.example.teacherassistantai.service.HierarchicalMarkdownChunk;
import com.example.teacherassistantai.service.MarkdownChunkingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentPhase0BaselineSnapshotTest {

    private static final Pattern IMAGE_BASE64_PATTERN = Pattern.compile("(?im)^\\s*Image:base64,.*$");
    private static final Pattern DATA_URI_IMAGE_PATTERN = Pattern.compile("(?i)data:image/[^;]+;base64,[a-zA-Z0-9+/=]+", Pattern.MULTILINE);
    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{1,6}\\s+(.+)$");
    private static final Pattern SINGLE_NUMBER_HEADING_PATTERN = Pattern.compile("^#{1,6}\\s+\\d{1,2}\\.\\s+.+$");
    private static final Pattern FOOTNOTE_LINE_PATTERN = Pattern.compile("^\\d{1,3}\\.?\\s+\\S.+$");
    private static final Pattern FOOTNOTE_STRONG_SIGNAL_PATTERN = Pattern.compile("(?iu).*(nxb\\.?|toàn\\s+tập|văn\\s+kiện|\\btrang\\s+\\d|\\btr\\.?\\s*\\d|\\bt\\.?\\s*\\d|sđd|dẫn\\s+theo|xem\\s+sđd).*");
    private static final Pattern REVIEW_HEADING_LEAK_PATTERN = Pattern.compile("(?m)^#{2,6}\\s+\\d{1,2}\\.\\s+.+$");
    private static final Pattern CHUNK_MARKDOWN_HEADING_PATTERN = Pattern.compile("(?m)^#{2,6}\\s+.+$");
    private static final Pattern LEAD_IN_PATTERN = Pattern.compile("(?iu).*(bao\\s+g[oồ]m|như\\s+sau|g[oồ]m|sau\\s+đây)\\s*:\\s*$");
    private static final Pattern PART_TITLE_PATTERN = Pattern.compile("(?iu)^phần\\s+[ivxlcdm]+\\b.*$");
    private static final Pattern CHAPTER_TITLE_PATTERN = Pattern.compile("(?iu)^chương\\s+([0-9ivxlcdm]+|nhập\\s+môn)\\b.*$");
    private static final Pattern ROMAN_SECTION_PATTERN = Pattern.compile("(?iu)^[ivxlcdm]+[.-]\\s+.+$");
    private static final Pattern DECIMAL_SECTION_PATTERN = Pattern.compile("^\\d+(?:\\.\\d+)+\\.?\\s+.+$");
    private static final Pattern SINGLE_NUMBERED_PATTERN = Pattern.compile("^\\d{1,2}\\.\\s+.+$");
    private static final Pattern SUMMARY_HEADING_PATTERN = Pattern.compile("(?iu)^tóm\\s+tắt\\s+chương.*$");
    private static final Pattern REVIEW_HEADING_PATTERN = Pattern.compile("(?iu)^(câu\\s+hỏi\\s+ôn\\s+tập|nội\\s+dung\\s+ôn\\s+tập.*).*$");
    private static final Pattern CONCLUSION_PATTERN = Pattern.compile("(?iu)^kết\\s+luận$");
    private static final DateTimeFormatter RUN_ID_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String DEFAULT_ROOT_TITLE = "Document";

    private static final List<String> INPUT_FILES = List.of(
            "gt-lich-su-dang-csvn-ban-tuyen-giao-tw.pdf",
            "Bài giảng Pháp luật đại cương Th.S Lê Thị Bích Ngọc.pdf",
            "75770b9b-cdbf-4038-90e2-f25e1f4426fe_triethocmaclenin.pdf"
    );

    private final TikaMarkdownParser parser = new TikaMarkdownParser(
            ingestionProps(),
            new PdfMarkdownPostProcessor()
    );
    private final MarkdownChunkingService chunkingService = new MarkdownChunkingService();
    private final DocumentHierarchyArtifactService artifactService = new DocumentHierarchyArtifactService(chunkingService);
    private final DocumentHierarchyArtifactValidationService validationService = new DocumentHierarchyArtifactValidationService();
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void generatePhase0BaselineSnapshotsForInputFiles() throws Exception {
        Assumptions.assumeTrue(isPhase0Enabled(),
                "Skip phase0 baseline snapshot generator. Enable with -Dphase0.run=true");
        Path inputDir = Path.of("input");
        Assumptions.assumeTrue(Files.isDirectory(inputDir), "input directory is not available");

        String runId = resolveRunId();
        if (runId == null || runId.isBlank()) {
            runId = LocalDateTime.now().format(RUN_ID_FORMATTER);
        }
        Path runDir = Path.of("output", "phase0-baseline", runId);
        Files.createDirectories(runDir);

        List<Map<String, Object>> allMetrics = new ArrayList<>();
        List<Map<String, Object>> allValidation = new ArrayList<>();

        int docId = 1;
        for (String inputFile : INPUT_FILES) {
            Path source = inputDir.resolve(inputFile);
            Assumptions.assumeTrue(Files.isRegularFile(source), "Missing sample input file: " + source);

            String baseName = stripExtension(inputFile);
            String slug = toSlug(baseName);
            Path docDir = runDir.resolve(slug);
            Files.createDirectories(docDir);

            byte[] bytes = Files.readAllBytes(source);
            String rawMarkdown = parser.parseToMarkdown(bytes, baseName, "application/pdf");
            String sanitizedMarkdown = sanitizeMarkdown(rawMarkdown);

            Document document = Document.builder()
                    .title(baseName)
                    .originalObjectKey("phase0/input/" + inputFile)
                    .markdownObjectKey("phase0/output/" + slug + ".md")
                    .fileType("PDF")
                    .fileSizeBytes((long) bytes.length)
                    .build();
            document.setId((long) docId++);

            DocumentHierarchyArtifactService.Artifacts artifacts = artifactService.buildArtifacts(document, sanitizedMarkdown);
            Phase0Analysis analysis = analyzeArtifacts(document, artifacts);
            Map<String, Object> validation = validateArtifacts(document, artifacts, analysis);
            Map<String, Object> metrics = collectMetrics(
                    inputFile,
                    bytes.length,
                    rawMarkdown,
                    sanitizedMarkdown,
                    artifacts,
                    analysis,
                    validation
            );
            String regressionMarkdown = buildRegressionCasesMarkdown(artifacts, metrics);

            Files.writeString(docDir.resolve("01_raw_markdown.md"), rawMarkdown, StandardCharsets.UTF_8);
            Files.writeString(docDir.resolve("02_sanitized_markdown.md"), sanitizedMarkdown, StandardCharsets.UTF_8);
            Files.writeString(docDir.resolve("03_normalized_markdown.md"), artifacts.normalizedMarkdown(), StandardCharsets.UTF_8);
            Files.writeString(docDir.resolve("04_hierarchy.json"), artifacts.hierarchyJson(), StandardCharsets.UTF_8);
            Files.writeString(docDir.resolve("05_chunks.jsonl"), artifacts.chunksJsonl(), StandardCharsets.UTF_8);
            Files.writeString(docDir.resolve("06_metrics.json"), objectMapper.writeValueAsString(metrics), StandardCharsets.UTF_8);
            Files.writeString(docDir.resolve("07_regression_cases.md"), regressionMarkdown, StandardCharsets.UTF_8);
            Files.writeString(docDir.resolve("08_validation.json"), objectMapper.writeValueAsString(validation), StandardCharsets.UTF_8);

            allMetrics.add(metrics);
            allValidation.add(validation);
        }

        Files.writeString(runDir.resolve("phase0-metrics.json"), objectMapper.writeValueAsString(allMetrics), StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("phase0-validation.json"), objectMapper.writeValueAsString(allValidation), StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("README.md"), buildReadme(runId, allMetrics, allValidation), StandardCharsets.UTF_8);

        assertThat(allMetrics).hasSize(INPUT_FILES.size());
        assertThat(failedValidations(allValidation))
                .describedAs("Phase 7 acceptance validation must pass for all input files")
                .isEmpty();
    }

    private static boolean isPhase0Enabled() {
        String systemValue = System.getProperty("phase0.run");
        if (systemValue != null && !systemValue.isBlank()) {
            return Boolean.parseBoolean(systemValue);
        }
        String envValue = System.getenv("PHASE0_RUN");
        return envValue != null && Boolean.parseBoolean(envValue);
    }

    private static String resolveRunId() {
        String systemValue = System.getProperty("phase0.runId");
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue;
        }
        String envValue = System.getenv("PHASE0_RUN_ID");
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        return null;
    }

    private Map<String, Object> validateArtifacts(Document document,
                                                  DocumentHierarchyArtifactService.Artifacts artifacts,
                                                  Phase0Analysis analysis) {
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("documentTitle", document.getTitle());
        validation.put("documentId", document.getId());
        validation.put("status", "PASSED");
        validation.put("errorCount", 0);
        validation.put("errors", List.of());
        validation.put("warningCount", 0);
        validation.put("rawWarningCount", 0);
        validation.put("warnings", List.of());
        validation.put("errorMessage", null);

        List<String> errors = new ArrayList<>();
        List<String> warnings = List.of();
        try {
            DocumentHierarchyArtifactValidationService.ValidationReport report = validationService.validate(document, artifacts);
            validation.put("rawWarningCount", report.warningCount());
            warnings = summarizeWarnings(report.warnings());
        } catch (InvalidDataException ex) {
            errors.add(ex.getMessage());
        }

        if (analysis.nodeWithoutDescendantChunkCount() > 0) {
            errors.add("nodes without any descendant chunks: %d; samples=%s"
                    .formatted(analysis.nodeWithoutDescendantChunkCount(), sampleNodeIds(analysis.nodeWithoutDescendantChunkSamples(), 5)));
        }
        if (analysis.leafNodeWithoutChunkCount() > 0) {
            errors.add("leaf nodes without direct chunks: %d; samples=%s"
                    .formatted(analysis.leafNodeWithoutChunkCount(), sampleNodeIds(analysis.leafNodeWithoutChunkSamples(), 5)));
        }
        if (analysis.singleNumberHeadingAfterLeadinCount() > 0) {
            errors.add("single-number headings still promoted after lead-in: %d; lines=%s"
                    .formatted(analysis.singleNumberHeadingAfterLeadinCount(), sampleLineNumbers(analysis.singleNumberHeadingAfterLeadinSamples(), 8)));
        }
        if (analysis.textChunkWithFootnoteCount() > 0) {
            errors.add("TEXT chunks still contain footnote/reference blocks: %d; chunkIndices=%s"
                    .formatted(analysis.textChunkWithFootnoteCount(), sampleIntValues(analysis.textChunkWithFootnoteIndices(), 8)));
        }
        if (analysis.chunkMarkdownHeadingLeakCount() > 0) {
            errors.add("chunk content still contains markdown heading markers: %d; byChunkType=%s"
                    .formatted(analysis.chunkMarkdownHeadingLeakCount(), analysis.chunkMarkdownHeadingLeakByChunkType()));
        }
        if (analysis.rootTitleIsDefaultDocument()) {
            errors.add("root title is still default `%s`".formatted(DEFAULT_ROOT_TITLE));
        }

        validation.put("warningCount", warnings.size());
        validation.put("warnings", warnings);
        validation.put("errorCount", errors.size());
        validation.put("errors", errors);
        if (!errors.isEmpty()) {
            validation.put("status", "FAILED");
            validation.put("errorMessage", String.join("; ", errors));
        }
        return validation;
    }

    private Map<String, Object> collectMetrics(String inputFile,
                                               int sourceBytes,
                                               String rawMarkdown,
                                               String sanitizedMarkdown,
                                               DocumentHierarchyArtifactService.Artifacts artifacts,
                                               Phase0Analysis analysis,
                                               Map<String, Object> validation) {
        List<MarkdownChunkingService.PublicHierarchyNode> nodes = flattenNodes(artifacts.hierarchyDocument().root());
        List<HierarchicalMarkdownChunk> chunks = artifacts.chunks();

        Map<String, Integer> nodeTypeCounts = countBy(nodes, MarkdownChunkingService.PublicHierarchyNode::nodeType);
        Map<String, Integer> chunkTypeCounts = countBy(chunks, HierarchicalMarkdownChunk::chunkType);

        long emptyNodeCount = nodes.stream()
                .filter(node -> node.contentCharCount() == null || node.contentCharCount() == 0)
                .count();
        long singleNumberHeadingCount = sanitizedMarkdown.lines()
                .map(String::trim)
                .filter(line -> SINGLE_NUMBER_HEADING_PATTERN.matcher(line).matches())
                .count();

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("inputFile", inputFile);
        metrics.put("sourceBytes", sourceBytes);
        metrics.put("rawMarkdownChars", rawMarkdown.length());
        metrics.put("sanitizedMarkdownChars", sanitizedMarkdown.length());
        metrics.put("normalizedMarkdownChars", artifacts.normalizedMarkdown().length());
        metrics.put("nodeCount", nodes.size());
        metrics.put("emptyNodeCount", emptyNodeCount);
        metrics.put("chunkCount", chunks.size());
        metrics.put("nodeTypeCounts", nodeTypeCounts);
        metrics.put("chunkTypeCounts", chunkTypeCounts);
        metrics.put("citationChunkCount", chunkTypeCounts.getOrDefault("CITATION", 0));
        metrics.put("singleNumberHeadingCount", singleNumberHeadingCount);
        metrics.put("singleNumberHeadingAfterLeadinCount", analysis.singleNumberHeadingAfterLeadinCount());
        metrics.put("singleNumberHeadingAfterLeadinSamples", analysis.singleNumberHeadingAfterLeadinSamples());
        metrics.put("invalidHeadingCandidateCount", analysis.invalidHeadingCandidateCount());
        metrics.put("invalidHeadingCandidateSamples", analysis.invalidHeadingSamples());
        metrics.put("leafNodeWithoutChunkCount", analysis.leafNodeWithoutChunkCount());
        metrics.put("leafNodeWithoutChunkSamples", analysis.leafNodeWithoutChunkSamples());
        metrics.put("nodeWithoutDescendantChunkCount", analysis.nodeWithoutDescendantChunkCount());
        metrics.put("nodeWithoutDescendantChunkSamples", analysis.nodeWithoutDescendantChunkSamples());
        metrics.put("textChunkWithFootnoteCount", analysis.textChunkWithFootnoteCount());
        metrics.put("textChunkWithFootnoteIndices", analysis.textChunkWithFootnoteIndices());
        metrics.put("textChunkWithFootnoteSamples", analysis.textChunkWithFootnoteSamples());
        metrics.put("reviewQuestionHeadingLeakCount", analysis.reviewQuestionHeadingLeakCount());
        metrics.put("reviewQuestionHeadingLeakIndices", analysis.reviewQuestionHeadingLeakIndices());
        metrics.put("reviewQuestionHeadingLeakSamples", analysis.reviewQuestionHeadingLeakSamples());
        metrics.put("chunkMarkdownHeadingLeakCount", analysis.chunkMarkdownHeadingLeakCount());
        metrics.put("chunkMarkdownHeadingLeakByChunkType", analysis.chunkMarkdownHeadingLeakByChunkType());
        metrics.put("chunkMarkdownHeadingLeakIndices", analysis.chunkMarkdownHeadingLeakIndices());
        metrics.put("chunkMarkdownHeadingLeakSamples", analysis.chunkMarkdownHeadingLeakSamples());
        metrics.put("rootTitle", analysis.rootTitle());
        metrics.put("rootTitleMatchesDocumentTitle", analysis.rootTitleMatchesDocumentTitle());
        metrics.put("rootTitleIsDefaultDocument", analysis.rootTitleIsDefaultDocument());
        metrics.put("validationStatus", validation.get("status"));
        metrics.put("validationWarningCount", validation.get("warningCount"));
        metrics.put("validationRawWarningCount", validation.get("rawWarningCount"));
        metrics.put("validationErrorCount", validation.get("errorCount"));
        metrics.put("validationErrorMessage", validation.get("errorMessage"));
        return metrics;
    }

    private String buildRegressionCasesMarkdown(DocumentHierarchyArtifactService.Artifacts artifacts, Map<String, Object> metrics) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Regression Cases\n\n");

        builder.append("## Suspected List-As-Heading Cases\n\n");
        appendSampleSection(builder, castList(metrics.get("singleNumberHeadingAfterLeadinSamples")));

        builder.append("\n## Suspected Invalid Heading Cases\n\n");
        appendSampleSection(builder, castList(metrics.get("invalidHeadingCandidateSamples")));

        builder.append("\n## Footnote Block Inside TEXT Chunks\n\n");
        appendSampleSection(builder, castList(metrics.get("textChunkWithFootnoteSamples")));

        builder.append("\n## Leaf Nodes Without Chunks\n\n");
        appendSampleSection(builder, castList(metrics.get("leafNodeWithoutChunkSamples")));

        builder.append("\n## Chunk Markdown Heading Leak\n\n");
        appendSampleSection(builder, castList(metrics.get("chunkMarkdownHeadingLeakSamples")));

        builder.append("\n## REVIEW_QUESTIONS Heading Leak (`#####`)\n\n");
        appendSampleSection(builder, castList(metrics.get("reviewQuestionHeadingLeakSamples")));

        builder.append("\n## Summary\n\n");
        builder.append("- nodeCount: ").append(metrics.get("nodeCount")).append('\n');
        builder.append("- chunkCount: ").append(metrics.get("chunkCount")).append('\n');
        builder.append("- emptyNodeCount: ").append(metrics.get("emptyNodeCount")).append('\n');
        builder.append("- leafNodeWithoutChunkCount: ").append(metrics.get("leafNodeWithoutChunkCount")).append('\n');
        builder.append("- nodeWithoutDescendantChunkCount: ").append(metrics.get("nodeWithoutDescendantChunkCount")).append('\n');
        builder.append("- singleNumberHeadingAfterLeadinCount: ").append(metrics.get("singleNumberHeadingAfterLeadinCount")).append('\n');
        builder.append("- invalidHeadingCandidateCount: ").append(metrics.get("invalidHeadingCandidateCount")).append('\n');
        builder.append("- textChunkWithFootnoteCount: ").append(metrics.get("textChunkWithFootnoteCount")).append('\n');
        builder.append("- chunkMarkdownHeadingLeakCount: ").append(metrics.get("chunkMarkdownHeadingLeakCount")).append('\n');
        builder.append("- reviewQuestionHeadingLeakCount: ").append(metrics.get("reviewQuestionHeadingLeakCount")).append('\n');
        builder.append("- validationStatus: ").append(metrics.get("validationStatus")).append('\n');
        builder.append("- validationErrorCount: ").append(metrics.get("validationErrorCount")).append('\n');

        if (artifacts.hierarchyDocument().root() != null) {
            builder.append("- rootTitle: `")
                    .append(artifacts.hierarchyDocument().root().title())
                    .append("`\n");
        }
        return builder.toString();
    }

    private String buildReadme(String runId, List<Map<String, Object>> allMetrics, List<Map<String, Object>> allValidation) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Phase 0 Baseline Snapshot\n\n");
        builder.append("- runId: `").append(runId).append("`\n");
        builder.append("- generatedAt: `").append(LocalDateTime.now()).append("`\n");
        builder.append("- inputCount: ").append(allMetrics.size()).append("\n\n");

        builder.append("## Output Layout\n\n");
        builder.append("- `<slug>/01_raw_markdown.md`\n");
        builder.append("- `<slug>/02_sanitized_markdown.md`\n");
        builder.append("- `<slug>/03_normalized_markdown.md`\n");
        builder.append("- `<slug>/04_hierarchy.json`\n");
        builder.append("- `<slug>/05_chunks.jsonl`\n");
        builder.append("- `<slug>/06_metrics.json`\n");
        builder.append("- `<slug>/07_regression_cases.md`\n");
        builder.append("- `<slug>/08_validation.json`\n");
        builder.append("- `phase0-metrics.json`\n");
        builder.append("- `phase0-validation.json`\n\n");

        builder.append("## Snapshot Summary\n\n");
        builder.append("| inputFile | nodeCount | chunkCount | citationChunkCount | leafNodeWithoutChunkCount | singleNumberHeadingAfterLeadinCount | textChunkWithFootnoteCount | chunkMarkdownHeadingLeakCount | rootTitleMatchesDocumentTitle | validationStatus |\n");
        builder.append("|---|---:|---:|---:|---:|---:|---:|---:|:---:|---|\n");
        for (Map<String, Object> metrics : allMetrics) {
            builder.append('|').append(metrics.get("inputFile"))
                    .append('|').append(metrics.get("nodeCount"))
                    .append('|').append(metrics.get("chunkCount"))
                    .append('|').append(metrics.get("citationChunkCount"))
                    .append('|').append(metrics.get("leafNodeWithoutChunkCount"))
                    .append('|').append(metrics.get("singleNumberHeadingAfterLeadinCount"))
                    .append('|').append(metrics.get("textChunkWithFootnoteCount"))
                    .append('|').append(metrics.get("chunkMarkdownHeadingLeakCount"))
                    .append('|').append(metrics.get("rootTitleMatchesDocumentTitle"))
                    .append('|').append(metrics.get("validationStatus"))
                    .append("|\n");
        }

        long failed = failedValidations(allValidation).size();
        builder.append("\n## Acceptance Gates\n\n");
        builder.append("- validationFailedCount: ").append(failed).append('\n');
        builder.append("- leafNodeWithoutChunkCount: ").append(sumMetric(allMetrics, "leafNodeWithoutChunkCount")).append('\n');
        builder.append("- singleNumberHeadingAfterLeadinCount: ").append(sumMetric(allMetrics, "singleNumberHeadingAfterLeadinCount")).append('\n');
        builder.append("- textChunkWithFootnoteCount: ").append(sumMetric(allMetrics, "textChunkWithFootnoteCount")).append('\n');
        builder.append("- chunkMarkdownHeadingLeakCount: ").append(sumMetric(allMetrics, "chunkMarkdownHeadingLeakCount")).append('\n');
        builder.append("- rootTitleMismatchCount: ").append(rootTitleMismatchCount(allMetrics)).append('\n');
        return builder.toString();
    }

    private static List<Map<String, Object>> failedValidations(List<Map<String, Object>> allValidation) {
        return allValidation.stream()
                .filter(validation -> !"PASSED".equals(validation.get("status")))
                .toList();
    }

    private static long rootTitleMismatchCount(List<Map<String, Object>> allMetrics) {
        return allMetrics.stream()
                .filter(metrics -> !Boolean.TRUE.equals(metrics.get("rootTitleMatchesDocumentTitle")))
                .count();
    }

    private static long sumMetric(List<Map<String, Object>> allMetrics, String key) {
        return allMetrics.stream()
                .map(metrics -> metrics.get(key))
                .mapToLong(DocumentPhase0BaselineSnapshotTest::longValue)
                .sum();
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static String sanitizeMarkdown(String markdown) {
        String cleaned = IMAGE_BASE64_PATTERN.matcher(markdown == null ? "" : markdown).replaceAll("");
        cleaned = DATA_URI_IMAGE_PATTERN.matcher(cleaned).replaceAll("[removed-base64-image]");
        return cleaned.trim();
    }

    private static DocumentIngestionProps ingestionProps() {
        DocumentIngestionProps props = new DocumentIngestionProps();
        props.setMaxDocxBytes(102_400);
        props.setMaxTxtBytes(102_400);
        props.setParseConcurrency(2);
        props.setTikaMaxChars(5_000_000);
        return props;
    }

    private static String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private static String toSlug(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("(^-+|-+$)", "");
        return normalized.isBlank() ? "document" : normalized;
    }

    private static List<MarkdownChunkingService.PublicHierarchyNode> flattenNodes(MarkdownChunkingService.PublicHierarchyNode root) {
        if (root == null) {
            return List.of();
        }
        List<MarkdownChunkingService.PublicHierarchyNode> nodes = new ArrayList<>();
        collectNodes(root, nodes);
        return nodes;
    }

    private static void collectNodes(MarkdownChunkingService.PublicHierarchyNode node,
                                     List<MarkdownChunkingService.PublicHierarchyNode> output) {
        output.add(node);
        for (MarkdownChunkingService.PublicHierarchyNode child : node.children()) {
            collectNodes(child, output);
        }
    }

    private static <T> Map<String, Integer> countBy(List<T> values, java.util.function.Function<T, String> keyExtractor) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        values.stream()
                .map(keyExtractor)
                .map(key -> key == null || key.isBlank() ? "UNKNOWN" : key)
                .sorted(Comparator.naturalOrder())
                .forEach(key -> counts.merge(key, 1, Integer::sum));
        return counts;
    }

    private Phase0Analysis analyzeArtifacts(Document document,
                                            DocumentHierarchyArtifactService.Artifacts artifacts) {
        List<HierarchicalMarkdownChunk> chunks = artifacts.chunks();
        Map<String, Integer> chunkCountByNodeId = countByNodeId(chunks);

        List<Map<String, Object>> leafNodeWithoutChunkSamples = new ArrayList<>();
        List<Map<String, Object>> nodeWithoutDescendantChunkSamples = new ArrayList<>();
        collectNodeChunkCoverage(
                artifacts.hierarchyDocument().root(),
                chunkCountByNodeId,
                leafNodeWithoutChunkSamples,
                nodeWithoutDescendantChunkSamples
        );

        List<Integer> textChunkWithFootnoteIndices = new ArrayList<>();
        List<Integer> reviewQuestionHeadingLeakIndices = new ArrayList<>();
        List<Integer> chunkMarkdownHeadingLeakIndices = new ArrayList<>();
        List<Map<String, Object>> textChunkWithFootnoteSamples = new ArrayList<>();
        List<Map<String, Object>> reviewHeadingLeakSamples = new ArrayList<>();
        List<Map<String, Object>> chunkMarkdownHeadingLeakSamples = new ArrayList<>();
        Map<String, Integer> chunkMarkdownHeadingLeakByChunkType = new LinkedHashMap<>();

        for (int i = 0; i < chunks.size(); i++) {
            HierarchicalMarkdownChunk chunk = chunks.get(i);
            int chunkIndex = i + 1;

            if ("TEXT".equals(chunk.chunkType()) && containsFootnoteBlock(chunk.content())) {
                textChunkWithFootnoteIndices.add(chunkIndex);
                addSample(textChunkWithFootnoteSamples, chunkSample(chunkIndex, chunk, chunk.content()), 12);
            }
            if ("REVIEW_QUESTIONS".equals(chunk.chunkType()) && REVIEW_HEADING_LEAK_PATTERN.matcher(chunk.content()).find()) {
                reviewQuestionHeadingLeakIndices.add(chunkIndex);
                addSample(reviewHeadingLeakSamples, chunkSample(chunkIndex, chunk, chunk.content()), 12);
            }
            if (CHUNK_MARKDOWN_HEADING_PATTERN.matcher(chunk.content()).find()) {
                chunkMarkdownHeadingLeakIndices.add(chunkIndex);
                chunkMarkdownHeadingLeakByChunkType.merge(chunk.chunkType(), 1, Integer::sum);
                addSample(chunkMarkdownHeadingLeakSamples, chunkSample(chunkIndex, chunk, chunk.content()), 12);
            }
        }

        List<String> markdownLines = artifacts.normalizedMarkdown().lines().toList();
        List<Map<String, Object>> singleNumberHeadingAfterLeadinSamples = collectSingleNumberHeadingAfterLeadinSamples(markdownLines);
        List<Map<String, Object>> invalidHeadingSamples = collectInvalidHeadingSamples(markdownLines);
        String rootTitle = artifacts.hierarchyDocument().root() == null ? null : artifacts.hierarchyDocument().root().title();

        return new Phase0Analysis(
                rootTitle,
                document.getTitle() != null && document.getTitle().equals(rootTitle),
                DEFAULT_ROOT_TITLE.equals(rootTitle),
                leafNodeWithoutChunkSamples.size(),
                List.copyOf(leafNodeWithoutChunkSamples),
                nodeWithoutDescendantChunkSamples.size(),
                List.copyOf(nodeWithoutDescendantChunkSamples),
                textChunkWithFootnoteIndices.size(),
                List.copyOf(textChunkWithFootnoteIndices),
                List.copyOf(textChunkWithFootnoteSamples),
                reviewQuestionHeadingLeakIndices.size(),
                List.copyOf(reviewQuestionHeadingLeakIndices),
                List.copyOf(reviewHeadingLeakSamples),
                chunkMarkdownHeadingLeakIndices.size(),
                new LinkedHashMap<>(chunkMarkdownHeadingLeakByChunkType),
                List.copyOf(chunkMarkdownHeadingLeakIndices),
                List.copyOf(chunkMarkdownHeadingLeakSamples),
                singleNumberHeadingAfterLeadinSamples.size(),
                List.copyOf(singleNumberHeadingAfterLeadinSamples),
                invalidHeadingSamples.size(),
                List.copyOf(invalidHeadingSamples)
        );
    }

    private static Map<String, Integer> countByNodeId(List<HierarchicalMarkdownChunk> chunks) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (HierarchicalMarkdownChunk chunk : chunks) {
            if (chunk.nodeId() != null && !chunk.nodeId().isBlank()) {
                counts.merge(chunk.nodeId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static int collectNodeChunkCoverage(MarkdownChunkingService.PublicHierarchyNode node,
                                                Map<String, Integer> chunkCountByNodeId,
                                                List<Map<String, Object>> leafNodeWithoutChunkSamples,
                                                List<Map<String, Object>> nodeWithoutDescendantChunkSamples) {
        int ownChunkCount = chunkCountByNodeId.getOrDefault(node.nodeId(), 0);
        int totalChunkCount = ownChunkCount;
        for (MarkdownChunkingService.PublicHierarchyNode child : node.children()) {
            totalChunkCount += collectNodeChunkCoverage(child, chunkCountByNodeId, leafNodeWithoutChunkSamples, nodeWithoutDescendantChunkSamples);
        }

        if (!"document".equals(node.nodeType()) && totalChunkCount == 0) {
            addSample(nodeWithoutDescendantChunkSamples, nodeSample(node), 12);
        }
        if (!"document".equals(node.nodeType()) && node.children().isEmpty() && ownChunkCount == 0) {
            addSample(leafNodeWithoutChunkSamples, nodeSample(node), 12);
        }
        return totalChunkCount;
    }

    private static boolean containsFootnoteBlock(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        int matchCount = 0;
        for (String line : content.lines().toList()) {
            if (isReferenceFootnoteLine(line.trim())) {
                matchCount++;
                if (matchCount >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isReferenceFootnoteLine(String line) {
        if (line == null || !FOOTNOTE_LINE_PATTERN.matcher(line.trim()).matches()) {
            return false;
        }
        return leadingNumber(line) >= 20 || FOOTNOTE_STRONG_SIGNAL_PATTERN.matcher(line).matches();
    }

    private static int leadingNumber(String line) {
        java.util.regex.Matcher matcher = Pattern.compile("^(\\d{1,3})\\.?\\s+\\S.+$").matcher(line.trim());
        if (!matcher.matches()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static List<Map<String, Object>> collectSingleNumberHeadingAfterLeadinSamples(List<String> lines) {
        List<Map<String, Object>> samples = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (!SINGLE_NUMBER_HEADING_PATTERN.matcher(line).matches()) {
                continue;
            }
            String previous = previousNonBlank(lines, i - 1);
            if (previous != null && LEAD_IN_PATTERN.matcher(previous).matches()) {
                Map<String, Object> sample = new LinkedHashMap<>();
                sample.put("lineNumber", i + 1);
                sample.put("heading", line);
                sample.put("previousNonBlankLine", previous);
                samples.add(sample);
            }
        }
        return samples;
    }

    private static List<Map<String, Object>> collectInvalidHeadingSamples(List<String> lines) {
        List<Map<String, Object>> samples = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            Matcher matcher = HEADING_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String title = matcher.group(1).trim();
            if (isKnownHeadingType(title)) {
                continue;
            }
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("lineNumber", i + 1);
            sample.put("heading", line);
            sample.put("reason", "Heading does not match known structured/special heading patterns");
            samples.add(sample);
        }
        return samples;
    }

    private static boolean isKnownHeadingType(String headingTitle) {
        String title = headingTitle == null ? "" : headingTitle.trim();
        return PART_TITLE_PATTERN.matcher(title).matches()
                || CHAPTER_TITLE_PATTERN.matcher(title).matches()
                || ROMAN_SECTION_PATTERN.matcher(title).matches()
                || DECIMAL_SECTION_PATTERN.matcher(title).matches()
                || SINGLE_NUMBERED_PATTERN.matcher(title).matches()
                || SUMMARY_HEADING_PATTERN.matcher(title).matches()
                || REVIEW_HEADING_PATTERN.matcher(title).matches()
                || CONCLUSION_PATTERN.matcher(title).matches();
    }

    private static String previousNonBlank(List<String> lines, int startIndex) {
        for (int i = startIndex; i >= 0; i--) {
            String value = lines.get(i).trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String abbreviate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars - 3) + "...";
    }

    private static Map<String, Object> chunkSample(int chunkIndex, HierarchicalMarkdownChunk chunk, String content) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("chunkIndex", chunkIndex);
        sample.put("chunkType", chunk.chunkType());
        sample.put("nodeType", chunk.nodeType());
        sample.put("nodeId", chunk.nodeId());
        sample.put("sectionHeader", chunk.sectionHeader());
        sample.put("snippet", abbreviate(content, 460));
        return sample;
    }

    private static Map<String, Object> nodeSample(MarkdownChunkingService.PublicHierarchyNode node) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("nodeId", node.nodeId());
        sample.put("nodeType", node.nodeType());
        sample.put("heading", node.title());
        sample.put("breadcrumb", String.join(" > ", node.breadcrumb()));
        sample.put("pageFrom", node.pageFrom());
        sample.put("pageTo", node.pageTo());
        return sample;
    }

    private static void addSample(List<Map<String, Object>> samples, Map<String, Object> sample, int maxSize) {
        if (samples.size() < maxSize) {
            samples.add(sample);
        }
    }

    private static String sampleLineNumbers(List<Map<String, Object>> samples, int limit) {
        List<String> values = new ArrayList<>();
        for (Map<String, Object> sample : samples) {
            if (values.size() >= limit) {
                break;
            }
            Object value = sample.get("lineNumber");
            if (value != null) {
                values.add(String.valueOf(value));
            }
        }
        return values.toString();
    }

    private static String sampleNodeIds(List<Map<String, Object>> samples, int limit) {
        List<String> values = new ArrayList<>();
        for (Map<String, Object> sample : samples) {
            if (values.size() >= limit) {
                break;
            }
            Object nodeId = sample.get("nodeId");
            Object heading = sample.get("heading");
            if (nodeId != null) {
                values.add(nodeId + ":" + abbreviate(String.valueOf(heading), 60));
            }
        }
        return values.toString();
    }

    private static String sampleIntValues(List<Integer> values, int limit) {
        if (values.isEmpty()) {
            return "[]";
        }
        return values.stream()
                .limit(limit)
                .map(String::valueOf)
                .toList()
                .toString();
    }

    private static List<String> summarizeWarnings(List<String> rawWarnings) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String warning : rawWarnings) {
            String key = normalizeWarningKey(warning);
            counts.merge(key, 1, Integer::sum);
        }
        List<String> summaries = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            summaries.add(entry.getKey() + " (" + entry.getValue() + ")");
        }
        return summaries;
    }

    private static String normalizeWarningKey(String warning) {
        if (warning == null || warning.isBlank()) {
            return "unknown warning";
        }
        if (warning.startsWith("sectionHeader may contain attached body")) {
            return "sectionHeader may contain attached body";
        }
        if (warning.startsWith("breadcrumb may start with body sentence")) {
            return "breadcrumb may start with body sentence";
        }
        if (warning.startsWith("PDF generated chunk ") && warning.endsWith(" missing pageFrom/pageTo")) {
            return "PDF generated chunk missing pageFrom/pageTo";
        }
        if (warning.startsWith("PDF chunk line ") && warning.endsWith(" missing pageFrom/pageTo")) {
            return "PDF chunk line missing pageFrom/pageTo";
        }
        if (warning.startsWith("chunk ") && warning.contains(" exceeds ")) {
            return "chunk length exceeds threshold";
        }
        return warning;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> output = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> casted = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    casted.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                output.add(casted);
            }
        }
        return output;
    }

    private static void appendSampleSection(StringBuilder builder, List<Map<String, Object>> samples) {
        if (samples.isEmpty()) {
            builder.append("_No sample found._\n");
            return;
        }
        for (Map<String, Object> sample : samples) {
            builder.append("- ");
            Object lineNumber = sample.get("lineNumber");
            if (lineNumber != null) {
                builder.append("line ").append(lineNumber).append(": ");
            } else if (sample.get("chunkIndex") != null) {
                builder.append("chunk ").append(sample.get("chunkIndex")).append(": ");
            } else if (sample.get("nodeId") != null) {
                builder.append("node ").append(sample.get("nodeId")).append(": ");
            }
            Object heading = sample.get("heading");
            if (heading != null) {
                builder.append('`').append(heading).append('`').append('\n');
            } else if (sample.get("sectionHeader") != null) {
                builder.append('`').append(sample.get("sectionHeader")).append('`').append('\n');
            } else {
                builder.append('\n');
            }
            if (sample.get("previousNonBlankLine") != null) {
                builder.append("  prev: `").append(sample.get("previousNonBlankLine")).append("`\n");
            }
            if (sample.get("breadcrumb") != null) {
                builder.append("  breadcrumb: `").append(sample.get("breadcrumb")).append("`\n");
            }
            if (sample.get("reason") != null) {
                builder.append("  reason: ").append(sample.get("reason")).append('\n');
            }
            if (sample.get("snippet") != null) {
                builder.append("  snippet:\n\n");
                builder.append("  ```text\n");
                builder.append(String.valueOf(sample.get("snippet"))).append('\n');
                builder.append("  ```\n");
            }
        }
    }

    private record Phase0Analysis(
            String rootTitle,
            boolean rootTitleMatchesDocumentTitle,
            boolean rootTitleIsDefaultDocument,
            int leafNodeWithoutChunkCount,
            List<Map<String, Object>> leafNodeWithoutChunkSamples,
            int nodeWithoutDescendantChunkCount,
            List<Map<String, Object>> nodeWithoutDescendantChunkSamples,
            int textChunkWithFootnoteCount,
            List<Integer> textChunkWithFootnoteIndices,
            List<Map<String, Object>> textChunkWithFootnoteSamples,
            int reviewQuestionHeadingLeakCount,
            List<Integer> reviewQuestionHeadingLeakIndices,
            List<Map<String, Object>> reviewQuestionHeadingLeakSamples,
            int chunkMarkdownHeadingLeakCount,
            Map<String, Integer> chunkMarkdownHeadingLeakByChunkType,
            List<Integer> chunkMarkdownHeadingLeakIndices,
            List<Map<String, Object>> chunkMarkdownHeadingLeakSamples,
            int singleNumberHeadingAfterLeadinCount,
            List<Map<String, Object>> singleNumberHeadingAfterLeadinSamples,
            int invalidHeadingCandidateCount,
            List<Map<String, Object>> invalidHeadingSamples
    ) {
    }
}
