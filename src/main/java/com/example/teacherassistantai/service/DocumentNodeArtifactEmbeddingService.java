package com.example.teacherassistantai.service;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.integration.ai.AiEmbeddingGateway;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentNodeArtifactEmbeddingService {

    private static final int MAX_RETRIEVAL_TEXT_CHARS = 8_000;
    private static final int MAX_KEY_POINT_CHARS = 700;
    private static final int MAX_CHILD_SUMMARIES = 6;
    private static final int MAX_CHILD_SUMMARY_CHARS = 500;

    private final DocumentNodeArtifactRepository artifactRepository;
    private final AiEmbeddingGateway embeddingGateway;
    private final RagProperties ragProperties;
    private final TransactionTemplate transactionTemplate;
    private final Semaphore artifactEmbeddingThrottle = new Semaphore(1);

    @Async("documentEnrichmentExecutor")
    public void embedCompletedSummaryArtifactAsync(Long artifactId) {
        embedCompletedSummaryArtifact(artifactId);
    }

    @Async("documentEnrichmentExecutor")
    public void enqueueCompletedSummaryEmbeddingBackfill(Long documentId,
                                                         Long subjectId,
                                                         int batchSize,
                                                         int maxBatches) {
        int safeBatchSize = Math.max(1, batchSize);
        int safeMaxBatches = Math.max(1, maxBatches);
        for (int batch = 0; batch < safeMaxBatches; batch++) {
            int embedded = backfillCompletedSummaryEmbeddings(documentId, subjectId, safeBatchSize);
            if (embedded == 0) {
                log.info("Summary artifact embedding backfill stopped: documentId={}, subjectId={}, batch={}, embedded=0",
                        documentId, subjectId, batch + 1);
                break;
            }
        }
    }

    public boolean embedCompletedSummaryArtifact(Long artifactId) {
        if (artifactId == null) {
            return false;
        }
        Optional<DocumentNodeArtifact> artifact = artifactRepository.findCompletedSummaryForRetrievalEmbedding(artifactId);
        if (artifact.isEmpty()) {
            log.debug("Skip summary artifact embedding because artifact is not completed summary: artifactId={}", artifactId);
            return false;
        }

        String retrievalText = buildRetrievalText(artifact.get());
        if (!StringUtils.hasText(retrievalText)) {
            log.warn("Skip summary artifact embedding because retrieval text is empty: artifactId={}", artifactId);
            return false;
        }

        String retrievalTextHash = retrievalTextHash(retrievalText);
        String embeddingModel = embeddingModel();
        int embeddingDimensions = ragProperties.getEmbeddingDimensions();
        if (hasCurrentEmbedding(artifactId, retrievalTextHash, embeddingModel, embeddingDimensions)) {
            return true;
        }

        List<Double> embedding = embedThrottled(artifactId, retrievalText);
        if (!hasExpectedDimensions(artifactId, embedding, embeddingDimensions)) {
            return false;
        }

        String vectorLiteral = toVectorLiteral(embedding);
        Integer updated = transactionTemplate.execute(status -> artifactRepository.updateRetrievalEmbedding(
                artifactId,
                retrievalText,
                retrievalTextHash,
                vectorLiteral,
                embeddingModel,
                embeddingDimensions
        ));
        return updated != null && updated > 0;
    }

    public int backfillCompletedSummaryEmbeddings(int limit) {
        return backfillCompletedSummaryEmbeddings(null, null, limit);
    }

    public int backfillCompletedSummaryEmbeddings(Long documentId, Long subjectId, int limit) {
        int safeLimit = Math.max(1, limit);
        List<Long> artifactIds = artifactRepository.findCompletedSummaryIdsNeedingRetrievalEmbedding(
                documentId,
                subjectId,
                embeddingModel(),
                ragProperties.getEmbeddingDimensions(),
                safeLimit
        );
        int embedded = 0;
        for (Long artifactId : artifactIds) {
            if (embedCompletedSummaryArtifact(artifactId)) {
                embedded++;
            }
        }
        return embedded;
    }

    public RetrievalEmbeddingCoverage retrievalEmbeddingCoverage(Long documentId, Long subjectId) {
        String embeddingModel = embeddingModel();
        int embeddingDimensions = ragProperties.getEmbeddingDimensions();
        DocumentNodeArtifactRepository.RetrievalEmbeddingCoverageStats stats =
                artifactRepository.retrievalEmbeddingCoverageStats(
                        documentId,
                        subjectId,
                        embeddingModel,
                        embeddingDimensions
                );
        return new RetrievalEmbeddingCoverage(
                stats == null || stats.getTotalCompletedSummaries() == null ? 0L : stats.getTotalCompletedSummaries(),
                stats == null || stats.getEmbeddedCurrent() == null ? 0L : stats.getEmbeddedCurrent(),
                stats == null || stats.getPending() == null ? 0L : stats.getPending(),
                embeddingModel,
                embeddingDimensions
        );
    }

    public void clearRetrievalEmbedding(Long artifactId) {
        if (artifactId == null) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> artifactRepository.clearRetrievalEmbedding(artifactId));
    }

    String buildRetrievalText(DocumentNodeArtifact artifact) {
        Document document = artifact.getDocument();
        DocumentNode node = artifact.getDocumentNode();
        Map<String, Object> content = artifact.getContentJsonb() == null ? Map.of() : artifact.getContentJsonb();

        StringBuilder builder = new StringBuilder();
        appendLine(builder, "Document", document == null ? null : document.getTitle());
        appendLine(builder, "Path", node == null ? null : node.getSectionPath());
        appendLine(builder, "Node type", node == null ? null : node.getNodeType());
        appendLine(builder, "Title", node == null ? null : node.getTitle());
        appendLine(builder, "Summary", text(content.get("summary")));
        appendKeyPoints(builder, content.get("keyPoints"));
        appendChildSummaries(builder, content.get("childSummaries"));
        return limitChars(builder.toString().trim(), MAX_RETRIEVAL_TEXT_CHARS);
    }

    String retrievalTextHash(String retrievalText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(retrievalText.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is not available", ex);
        }
    }

    private boolean hasCurrentEmbedding(Long artifactId,
                                        String retrievalTextHash,
                                        String embeddingModel,
                                        int embeddingDimensions) {
        return artifactRepository.findRetrievalEmbeddingState(artifactId)
                .filter(state -> Boolean.TRUE.equals(state.getHasEmbedding()))
                .filter(state -> Objects.equals(state.getRetrievalTextHash(), retrievalTextHash))
                .filter(state -> Objects.equals(state.getEmbeddingModel(), embeddingModel))
                .filter(state -> Objects.equals(state.getEmbeddingDimensions(), embeddingDimensions))
                .isPresent();
    }

    private List<Double> embedThrottled(Long artifactId, String retrievalText) {
        try {
            artifactEmbeddingThrottle.acquire();
            try {
                return embeddingGateway.embed(retrievalText);
            } finally {
                artifactEmbeddingThrottle.release();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Summary artifact embedding interrupted: artifactId={}", artifactId);
            return List.of();
        } catch (Exception ex) {
            log.warn("Summary artifact embedding failed: artifactId={}", artifactId, ex);
            return List.of();
        }
    }

    private boolean hasExpectedDimensions(Long artifactId, List<Double> embedding, int expectedDimensions) {
        int actual = embedding == null ? 0 : embedding.size();
        if (actual != expectedDimensions) {
            log.warn("Summary artifact embedding dimension mismatch: artifactId={}, expected={}, actual={}",
                    artifactId, expectedDimensions, actual);
            return false;
        }
        return true;
    }

    private String embeddingModel() {
        String model = embeddingGateway.embeddingModel();
        if (StringUtils.hasText(model)) {
            return model;
        }
        return ragProperties.getAi().getEmbeddingModel();
    }

    private void appendLine(StringBuilder builder, String label, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        builder.append(label).append(": ").append(normalizeWhitespace(value)).append('\n');
    }

    private void appendKeyPoints(StringBuilder builder, Object rawKeyPoints) {
        List<String> keyPoints = textList(rawKeyPoints);
        if (keyPoints.isEmpty()) {
            return;
        }
        builder.append("Key points:\n");
        for (String keyPoint : keyPoints) {
            builder.append("- ")
                    .append(limitChars(normalizeWhitespace(keyPoint), MAX_KEY_POINT_CHARS))
                    .append('\n');
        }
    }

    private void appendChildSummaries(StringBuilder builder, Object rawChildSummaries) {
        if (!(rawChildSummaries instanceof List<?> values) || values.isEmpty()) {
            return;
        }
        builder.append("Child summaries:\n");
        int count = 0;
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> childSummary)) {
                continue;
            }
            String title = text(childSummary.get("title"));
            String summary = text(childSummary.get("summary"));
            if (!StringUtils.hasText(summary)) {
                continue;
            }
            if (StringUtils.hasText(title)) {
                builder.append("- ")
                        .append(normalizeWhitespace(title))
                        .append(": ");
            } else {
                builder.append("- ");
            }
            builder.append(limitChars(normalizeWhitespace(summary), MAX_CHILD_SUMMARY_CHARS)).append('\n');
            count++;
            if (count >= MAX_CHILD_SUMMARIES) {
                break;
            }
        }
    }

    private List<String> textList(Object raw) {
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            String text = text(value);
            if (StringUtils.hasText(text)) {
                result.add(text);
            }
        }
        return result;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String normalizeWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String limitChars(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private String toVectorLiteral(List<Double> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(values.get(i));
        }
        builder.append(']');
        return builder.toString();
    }

    public record RetrievalEmbeddingCoverage(
            long totalCompletedSummaries,
            long embeddedCurrent,
            long pending,
            String embeddingModel,
            int embeddingDimensions
    ) {
    }
}
