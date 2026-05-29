package com.example.teacherassistantai.service.quiz;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.integration.ai.AiModelRoutingService;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import com.example.teacherassistantai.service.ChildSummary;
import com.example.teacherassistantai.service.DocumentNodeScopeService;
import com.example.teacherassistantai.service.ReviewQuestionCountResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ReviewQuestionInputResolver {

    private final DocumentNodeRepository nodeRepository;
    private final DocumentNodeArtifactRepository artifactRepository;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentNodeScopeService nodeScopeService;
    private final RagProperties ragProperties;
    private final ReviewQuestionCountResolver questionCountResolver;
    private final AiModelRoutingService aiModelRoutingService;

    public ReviewQuestionGenerationContext resolve(DocumentNode node) {
        ReviewQuestionCountResolver.CountRange range = questionCountResolver.resolve(node == null ? null : node.getNodeType());
        List<DocumentNode> children = selectedChildren(node);
        if (!ragProperties.getEnrichment().getReviewQuestionMixedInput().isEnabled() || children.isEmpty()) {
            return rawContext(node, range);
        }
        return mixedContext(node, children, range);
    }

    private ReviewQuestionGenerationContext rawContext(DocumentNode node,
                                                       ReviewQuestionCountResolver.CountRange range) {
        DocumentNodeScopeService.NodeScope scope = nodeScopeService.getScope(node.getId());
        List<DocumentChunk> rawChunks = limited(scope.chunks(), ragProperties.getEnrichment().getMaxNodeChunks());
        ReviewQuestionCoverage coverage = ReviewQuestionCoverage.rawChunks(rawChunks.size(), rawChunks.size());
        int totalTarget = range.max();
        return new ReviewQuestionGenerationContext(
                document(node),
                node,
                QuizInputMode.RAW_CHUNKS,
                rawChunks,
                List.of(),
                Map.of(),
                Map.of(),
                rawChunks,
                range.min(),
                range.max(),
                totalTarget,
                0,
                coverage,
                sourceHash(node, QuizInputMode.RAW_CHUNKS, rawChunks, List.of(), Map.of(), Map.of(), rawChunks, range, totalTarget, 0, coverage)
        );
    }

    private ReviewQuestionGenerationContext mixedContext(DocumentNode node,
                                                         List<DocumentNode> children,
                                                         ReviewQuestionCountResolver.CountRange range) {
        List<ChildSummary> childSummaries = new ArrayList<>();
        Map<Long, List<DocumentChunk>> fallbackRawChunks = new LinkedHashMap<>();
        Map<Long, List<DocumentChunk>> representativeChildChunks = new LinkedHashMap<>();
        List<DocumentChunk> allowedCitationChunks = new ArrayList<>();
        String promptVersion = ragProperties.getEnrichment().getPromptVersion();
        String summaryModel = aiModelRoutingService.enrichmentModelFor(DocumentNodeArtifactType.SUMMARY);

        for (DocumentNode child : children) {
            Optional<DocumentNodeArtifact> summaryArtifact = artifactRepository.findLatestCompletedSummaryByNodeId(
                    child.getId(),
                    promptVersion,
                    summaryModel
            );
            if (summaryArtifact.isPresent()) {
                ChildSummary childSummary = toChildSummary(child, summaryArtifact.get());
                childSummaries.add(childSummary);
                allowedCitationChunks.addAll(citationChunks(childSummary));
            } else {
                List<DocumentChunk> fallbackChunks = childChunks(child, maxFallbackChunksPerChild());
                if (!fallbackChunks.isEmpty()) {
                    fallbackRawChunks.put(child.getId(), fallbackChunks);
                    allowedCitationChunks.addAll(fallbackChunks);
                }
            }

            List<DocumentChunk> representativeChunks = childChunks(child, maxRepresentativeChunksPerChild());
            if (!representativeChunks.isEmpty()) {
                representativeChildChunks.put(child.getId(), representativeChunks);
                allowedCitationChunks.addAll(representativeChunks);
            }
        }

        List<DocumentChunk> dedupedAllowedCitationChunks = dedupeAndSort(allowedCitationChunks);
        int totalTarget = range.max();
        int summaryTarget = childSummaries.isEmpty() && fallbackRawChunks.isEmpty()
                ? 0
                : Math.max(1, (int) Math.floor(totalTarget * summaryTargetRatio()));
        int representativeTarget = representativeChildChunks.isEmpty() ? 0 : totalTarget - summaryTarget;
        if (summaryTarget == 0) {
            representativeTarget = totalTarget;
        } else if (representativeTarget == 0) {
            summaryTarget = totalTarget;
        }

        int representedChildren = representativeChildChunks.size();
        boolean complete = children.size() == childSummaries.size() + fallbackRawChunks.size()
                && representedChildren == children.size()
                && !dedupedAllowedCitationChunks.isEmpty();
        ReviewQuestionCoverage coverage = new ReviewQuestionCoverage(
                children.size(),
                childSummaries.size(),
                fallbackRawChunks.size(),
                representedChildren,
                0,
                dedupedAllowedCitationChunks.size(),
                complete
        );
        return new ReviewQuestionGenerationContext(
                document(node),
                node,
                QuizInputMode.MIXED_CHILD_SUMMARIES_AND_REPRESENTATIVE_CHUNKS,
                List.of(),
                childSummaries,
                fallbackRawChunks,
                representativeChildChunks,
                dedupedAllowedCitationChunks,
                range.min(),
                range.max(),
                summaryTarget,
                representativeTarget,
                coverage,
                sourceHash(node, QuizInputMode.MIXED_CHILD_SUMMARIES_AND_REPRESENTATIVE_CHUNKS,
                        List.of(), childSummaries, fallbackRawChunks, representativeChildChunks,
                        dedupedAllowedCitationChunks, range, summaryTarget, representativeTarget, coverage)
        );
    }

    private List<DocumentNode> selectedChildren(DocumentNode node) {
        if (node == null || node.getId() == null) {
            return List.of();
        }
        String nodeType = String.valueOf(node.getNodeType());
        if ("chapter".equals(nodeType)) {
            return firstNonEmptyChildren(node, List.of("section", "subsection", "subsection_level2"));
        }
        if ("section".equals(nodeType)) {
            return firstNonEmptyChildren(node, List.of("subsection", "subsection_level2"));
        }
        if ("subsection".equals(nodeType)) {
            return childrenOfType(node, "subsection_level2");
        }
        return List.of();
    }

    private List<DocumentNode> firstNonEmptyChildren(DocumentNode node, List<String> candidateTypes) {
        for (String candidateType : candidateTypes) {
            List<DocumentNode> children = childrenOfType(node, candidateType);
            if (!children.isEmpty()) {
                return children;
            }
        }
        return List.of();
    }

    private List<DocumentNode> childrenOfType(DocumentNode node, String nodeType) {
        return nodeRepository.findByParentIdOrderByOrderIndexAsc(node.getId()).stream()
                .filter(child -> nodeType.equals(child.getNodeType()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private ChildSummary toChildSummary(DocumentNode child, DocumentNodeArtifact artifact) {
        Map<String, Object> content = artifact.getContentJsonb() == null ? Map.of() : artifact.getContentJsonb();
        Object rawCitations = content.get("citations");
        List<Map<String, Object>> citations = rawCitations instanceof List<?> values
                ? values.stream()
                .filter(Map.class::isInstance)
                .map(value -> (Map<String, Object>) value)
                .toList()
                : List.of();
        return new ChildSummary(
                child.getId(),
                child.getNodeType(),
                child.getTitle(),
                child.getSectionPath(),
                artifact.getId(),
                artifact.getSourceHash(),
                String.valueOf(content.getOrDefault("summary", "")),
                citations
        );
    }

    private List<DocumentChunk> citationChunks(ChildSummary childSummary) {
        Set<Long> chunkIds = new LinkedHashSet<>();
        for (Map<String, Object> citation : childSummary.citations() == null ? List.<Map<String, Object>>of() : childSummary.citations()) {
            Object value = citation.get("chunkId");
            if (value instanceof Number number) {
                chunkIds.add(number.longValue());
            }
        }
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        return dedupeAndSort(chunkRepository.findAllById(chunkIds));
    }

    private List<DocumentChunk> childChunks(DocumentNode child, int limit) {
        if (child == null || child.getId() == null) {
            return List.of();
        }
        return limited(nodeScopeService.getScope(child.getId()).chunks(), limit);
    }

    private List<DocumentChunk> limited(List<DocumentChunk> chunks, int limit) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        return dedupeAndSort(chunks).stream()
                .limit(Math.max(1, limit))
                .toList();
    }

    private List<DocumentChunk> dedupeAndSort(List<DocumentChunk> chunks) {
        Map<Long, DocumentChunk> byId = new LinkedHashMap<>();
        List<DocumentChunk> noId = new ArrayList<>();
        for (DocumentChunk chunk : chunks == null ? List.<DocumentChunk>of() : chunks) {
            if (chunk == null) {
                continue;
            }
            if (chunk.getId() == null) {
                noId.add(chunk);
            } else {
                byId.putIfAbsent(chunk.getId(), chunk);
            }
        }
        List<DocumentChunk> result = new ArrayList<>(byId.values());
        result.addAll(noId);
        result.sort(Comparator
                .comparingInt(this::sourceOrder)
                .thenComparing(chunk -> chunk.getId() == null ? Long.MAX_VALUE : chunk.getId()));
        return result;
    }

    private Document document(DocumentNode node) {
        // Do not return node.getDocument() here — it is a lazy proxy that may not be
        // initializable outside a transaction. The caller already loads Document separately.
        return null;
    }

    private int maxFallbackChunksPerChild() {
        return ragProperties.getEnrichment().getReviewQuestionMixedInput().getMaxFallbackChunksPerChild();
    }

    private int maxRepresentativeChunksPerChild() {
        return ragProperties.getEnrichment().getReviewQuestionMixedInput().getMaxRepresentativeChunksPerChild();
    }

    private double summaryTargetRatio() {
        double value = ragProperties.getEnrichment().getReviewQuestionMixedInput().getSummaryTargetRatio();
        if (value <= 0.0) {
            return 0.5;
        }
        return Math.min(value, 1.0);
    }

    private String sourceHash(DocumentNode node,
                              QuizInputMode inputMode,
                              List<DocumentChunk> rawChunks,
                              List<ChildSummary> childSummaries,
                              Map<Long, List<DocumentChunk>> fallbackRawChunks,
                              Map<Long, List<DocumentChunk>> representativeChildChunks,
                              List<DocumentChunk> allowedCitationChunks,
                              ReviewQuestionCountResolver.CountRange range,
                              int summaryTarget,
                              int representativeTarget,
                              ReviewQuestionCoverage coverage) {
        MessageDigest digest = sha256();
        update(digest, "nodeId", node == null ? null : node.getId());
        update(digest, "nodeType", node == null ? null : node.getNodeType());
        update(digest, "nodeKey", node == null ? null : node.getNodeKey());
        update(digest, "sectionPath", node == null ? null : node.getSectionPath());
        update(digest, "title", node == null ? null : node.getTitle());
        update(digest, "inputMode", inputMode);
        update(digest, "minQuestionCount", range.min());
        update(digest, "maxQuestionCount", range.max());
        update(digest, "summaryBasedTargetCount", summaryTarget);
        update(digest, "representativeTargetCount", representativeTarget);
        appendChunks(digest, "raw", rawChunks);
        for (ChildSummary childSummary : childSummaries) {
            update(digest, "childSummary.nodeId", childSummary.nodeId());
            update(digest, "childSummary.artifactId", childSummary.artifactId());
            update(digest, "childSummary.sourceHash", childSummary.sourceHash());
            update(digest, "childSummary.summary", childSummary.summary());
        }
        appendChunkMap(digest, "fallback", fallbackRawChunks);
        appendChunkMap(digest, "representative", representativeChildChunks);
        appendChunks(digest, "allowedCitation", allowedCitationChunks);
        update(digest, "coverage.expectedChildCount", coverage.expectedChildCount());
        update(digest, "coverage.usedChildSummaryCount", coverage.usedChildSummaryCount());
        update(digest, "coverage.fallbackChildCount", coverage.fallbackChildCount());
        update(digest, "coverage.representativeChildCount", coverage.representativeChildCount());
        update(digest, "coverage.rawChunkCount", coverage.rawChunkCount());
        update(digest, "coverage.allowedCitationChunkCount", coverage.allowedCitationChunkCount());
        update(digest, "coverage.complete", coverage.complete());
        return HexFormat.of().formatHex(digest.digest());
    }

    private void appendChunkMap(MessageDigest digest, String label, Map<Long, List<DocumentChunk>> chunksByNode) {
        for (Map.Entry<Long, List<DocumentChunk>> entry : chunksByNode.entrySet()) {
            update(digest, label + ".nodeId", entry.getKey());
            appendChunks(digest, label, entry.getValue());
        }
    }

    private void appendChunks(MessageDigest digest, String label, List<DocumentChunk> chunks) {
        for (DocumentChunk chunk : chunks == null ? List.<DocumentChunk>of() : chunks) {
            update(digest, label + ".chunkId", chunk.getId());
            update(digest, label + ".sourceOrder", chunk.getSourceOrder());
            update(digest, label + ".chunkIndex", chunk.getChunkIndex());
            update(digest, label + ".updatedAt", chunk.getUpdatedAt());
            update(digest, label + ".content", chunk.getContent());
        }
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is not available", ex);
        }
    }

    private void update(MessageDigest digest, String label, Object value) {
        digest.update(label.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '=');
        String stringValue = value instanceof LocalDateTime localDateTime
                ? localDateTime.toString()
                : String.valueOf(value);
        digest.update(stringValue.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }

    private int sourceOrder(DocumentChunk chunk) {
        if (chunk == null) {
            return Integer.MAX_VALUE;
        }
        if (chunk.getSourceOrder() != null) {
            return chunk.getSourceOrder();
        }
        if (chunk.getChunkIndex() != null) {
            return chunk.getChunkIndex();
        }
        return Integer.MAX_VALUE;
    }
}
