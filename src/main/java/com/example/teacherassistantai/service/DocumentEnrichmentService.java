package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentEnrichmentStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.exception.BackgroundRateLimitedException;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import org.springframework.data.redis.core.RedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentEnrichmentService {

    private static final Set<String> ENRICHABLE_NODE_TYPES = Set.of("part", "chapter", "section", "subsection", "subsection_level2");
    private static final List<String> SUMMARY_NODE_ORDER = List.of("subsection_level2", "subsection", "section", "chapter", "part");
    private static final String PHASE_4_GENERATOR_MISSING =
            "Artifact generation is not available until Phase 4 prompt/LLM generator is implemented";

    private final DocumentRepository documentRepository;
    private final DocumentNodeRepository documentNodeRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final DocumentNodeArtifactRepository artifactRepository;
    private final DocumentNodeScopeService nodeScopeService;
    private final RagProperties ragProperties;
    private final ObjectProvider<DocumentNodeArtifactGenerator> artifactGenerators;
    private final TransactionTemplate transactionTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    private final com.example.teacherassistantai.service.quiz.HierarchicalQuizEnrichmentService quizEnrichmentService;

    @Async("documentEnrichmentExecutor")
    public void enqueueDocumentEnrichment(Long documentId) {
        if (!ragProperties.getEnrichment().isAutoRunAfterReady()) {
            markSkipped(documentId, "Automatic enrichment is disabled");
            return;
        }
        enrichDocument(documentId, false);
    }

    @Async("documentEnrichmentExecutor")
    public void enqueueDocumentEnrichment(Long documentId,
                                          boolean forceRegenerate,
                                          Collection<DocumentNodeArtifactType> artifactTypes) {
        enrichDocument(documentId, forceRegenerate, artifactTypes);
    }

    @Async("documentEnrichmentExecutor")
    public void enqueueNodeEnrichment(Long documentNodeId,
                                      boolean forceRegenerate,
                                      Collection<DocumentNodeArtifactType> artifactTypes) {
        enrichNode(documentNodeId, forceRegenerate, artifactTypes);
    }

    public void enrichDocument(Long documentId, boolean forceRegenerate) {
        enrichDocument(documentId, forceRegenerate, null);
    }

    public void enrichDocument(Long documentId,
                               boolean forceRegenerate,
                               Collection<DocumentNodeArtifactType> requestedArtifactTypes) {
        if (!ragProperties.getEnrichment().isEnabled()) {
            markSkipped(documentId, "Document enrichment is disabled");
            return;
        }

        List<DocumentNodeArtifactType> artifactTypes = enabledArtifactTypes(requestedArtifactTypes);
        if (artifactTypes.isEmpty()) {
            markSkipped(documentId, "No enrichment artifact types are enabled");
            return;
        }

        Document document = markRunning(documentId, forceRegenerate);
        boolean wasSummarising = document.getStatus() == DocumentStatus.SUMMARISING;
        if (!isEnrichableDocumentStatus(document.getStatus())) {
            log.info("Skip document enrichment because document status is not enrichable: documentId={}, status={}",
                    documentId, document.getStatus());
            return;
        }

        List<DocumentNode> nodes = documentNodeRepository.findByDocumentIdAndNodeTypeInOrderByOrderIndexAsc(
                documentId,
                ENRICHABLE_NODE_TYPES.stream().toList()
        );
        if (nodes.isEmpty()) {
            markSkipped(documentId, "No enrichable hierarchy nodes found");
            return;
        }

        List<ArtifactOutcome> outcomes = new ArrayList<>();
        if (artifactTypes.contains(DocumentNodeArtifactType.SUMMARY)) {
            outcomes.addAll(enrichDocumentSummariesBottomUp(document, forceRegenerate));
        }

        List<DocumentNodeArtifactType> nonSummaryArtifactTypes = artifactTypes.stream()
                .filter(artifactType -> artifactType != DocumentNodeArtifactType.SUMMARY)
                .toList();
        for (DocumentNode node : nodes) {
            for (DocumentNodeArtifactType artifactType : nonSummaryArtifactTypes) {
                outcomes.add(enrichArtifact(node.getId(), artifactType, forceRegenerate));
            }
        }

        finalizeDocumentStatus(documentId, outcomes);

        // After SUMMARISING → READY: retry FAILED section summaries once (enables chain regen)
        if (wasSummarising
                && artifactTypes.contains(DocumentNodeArtifactType.SUMMARY)
                && outcomes.stream().anyMatch(ArtifactOutcome::failed)) {
            Document after = documentRepository.findById(documentId).orElse(null);
            if (after != null && after.getStatus() == DocumentStatus.READY) {
                long failedCount = outcomes.stream().filter(ArtifactOutcome::failed).count();
                log.info("SUMMARISING→READY with {} failed nodes — retrying failed section summaries: documentId={}",
                        failedCount, documentId);
                enqueueDocumentEnrichment(documentId, false, List.of(DocumentNodeArtifactType.SUMMARY));
            }
        }

        // Trigger BG quiz enrichment phase 1 after first SUMMARISING→READY
        if (wasSummarising && ragProperties.getEnrichment().isReviewQuestionsEnabled()
                && artifactTypes.contains(DocumentNodeArtifactType.SUMMARY)) {
            Document afterStatus = documentRepository.findById(documentId).orElse(null);
            if (afterStatus != null && afterStatus.getStatus() == DocumentStatus.READY) {
                quizEnrichmentService.enrichQuizPhase1(documentId);
            }
        }
    }

    public void enrichNode(Long documentNodeId, boolean forceRegenerate) {
        enrichNode(documentNodeId, forceRegenerate, null);
    }

    public void enrichNode(Long documentNodeId,
                           boolean forceRegenerate,
                           Collection<DocumentNodeArtifactType> requestedArtifactTypes) {
        DocumentNode node = documentNodeRepository.findById(documentNodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Document node not found with id: " + documentNodeId));
        Long documentId = node.getDocument().getId();
        if (!ragProperties.getEnrichment().isEnabled()) {
            markSkipped(documentId, "Document enrichment is disabled");
            return;
        }

        log.info("enrichNode start: nodeId={}, documentId={}, forceRegenerate={}, artifactTypes={}",
                documentNodeId, documentId, forceRegenerate, requestedArtifactTypes);
        markRunning(documentId, forceRegenerate);
        List<ArtifactOutcome> outcomes = enabledArtifactTypes(requestedArtifactTypes).stream()
                .map(artifactType -> artifactType == DocumentNodeArtifactType.SUMMARY
                        ? enrichSummaryArtifact(node, forceRegenerate)
                        : enrichArtifact(documentNodeId, artifactType, forceRegenerate))
                .toList();
        log.info("enrichNode done: nodeId={}, outcomes={}", documentNodeId, outcomes);
        // Node-level enrichment must NOT touch DocumentStatus — it only knows outcomes for 1 node,
        // so failed==total would incorrectly set the whole document to FAILED.
        finalizeNodeEnrichmentStatus(documentId, outcomes);
    }

    private void finalizeNodeEnrichmentStatus(Long documentId, List<ArtifactOutcome> outcomes) {
        transactionTemplate.executeWithoutResult(status -> {
            Document document = documentRepository.findById(documentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Document not found with id: " + documentId));
            ArtifactSummary summary = ArtifactSummary.from(outcomes);
            document.setEnrichmentCompletedAt(LocalDateTime.now());
            if (summary.failed() == 0 && summary.completed() > 0) {
                document.setEnrichmentStatus(DocumentEnrichmentStatus.ENRICHED);
                document.setEnrichmentError(null);
            } else if (summary.failed() > 0) {
                document.setEnrichmentStatus(DocumentEnrichmentStatus.PARTIAL_FAILED);
                document.setEnrichmentError("Node enrichment partially failed");
            }
            // DocumentStatus intentionally NOT changed — full document status
            // is only managed by enrichDocument / finalizeDocumentStatus.
            documentRepository.save(document);
        });
    }

    public void retryFailedArtifacts(Long documentId) {
        enrichDocument(documentId, false);
    }

    public OnDemandArtifactStatus prepareNodeArtifactGeneration(Long documentNodeId,
                                                                DocumentNodeArtifactType artifactType) {
        String lockKey = "artifact-lock:" + documentNodeId + ":" + artifactType.name();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofSeconds(30));
        if (!Boolean.TRUE.equals(acquired)) {
            return OnDemandArtifactStatus.IN_PROGRESS;
        }
        try {
            DocumentNodeScopeService.NodeScope scope = nodeScopeService.getScope(documentNodeId);
            DocumentNode node = scope.rootNode();
            Document document = loadDocumentForNode(node);
            String sourceHash = scope.sourceHash();
            String promptVersion = ragProperties.getEnrichment().getPromptVersion();
            String model = ragProperties.getAi().getChatModel();

            DocumentNodeArtifact existing = artifactRepository
                    .findByDocumentNodeIdAndArtifactTypeAndPromptVersionAndModelAndSourceHash(
                            documentNodeId,
                            artifactType,
                            promptVersion,
                            model,
                            sourceHash
                    )
                    .orElse(null);
            if (existing != null && existing.getStatus() == DocumentNodeArtifactStatus.COMPLETED) {
                return OnDemandArtifactStatus.COMPLETED;
            }
            if (existing != null && (existing.getStatus() == DocumentNodeArtifactStatus.PENDING
                    || existing.getStatus() == DocumentNodeArtifactStatus.RUNNING)) {
                return OnDemandArtifactStatus.IN_PROGRESS;
            }

            upsertArtifact(existing, document, node, artifactType, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.PENDING, pendingContent(node, artifactType, sourceHash), null, null);
            return OnDemandArtifactStatus.QUEUED;
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    private List<DocumentNodeArtifactType> enabledArtifactTypes(Collection<DocumentNodeArtifactType> requestedArtifactTypes) {
        if (requestedArtifactTypes != null && !requestedArtifactTypes.isEmpty()) {
            return requestedArtifactTypes.stream()
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
        }

        List<DocumentNodeArtifactType> artifactTypes = new ArrayList<>();
        if (ragProperties.getEnrichment().isSummaryEnabled()) {
            artifactTypes.add(DocumentNodeArtifactType.SUMMARY);
        }
        if (ragProperties.getEnrichment().isReviewQuestionsEnabled()) {
            artifactTypes.add(DocumentNodeArtifactType.REVIEW_QUESTION_SET);
        }
        return artifactTypes;
    }

    private List<ArtifactOutcome> enrichDocumentSummariesBottomUp(Document document, boolean forceRegenerate) {
        List<ArtifactOutcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        Set<Long> chaptersNeedingRegen = Collections.synchronizedSet(new LinkedHashSet<>());
        int concurrency = ragProperties.getEnrichment().getIntraDocumentConcurrency();

        for (String nodeType : SUMMARY_NODE_ORDER) {
            List<DocumentNode> nodes = documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(
                    document.getId(), nodeType);
            if (nodes.isEmpty()) continue;

            Semaphore semaphore = new Semaphore(concurrency);
            List<Thread> threads = nodes.stream().map(node ->
                Thread.ofVirtual().start(() -> {
                    try {
                        semaphore.acquire();
                        try {
                            ArtifactOutcome outcome = enrichSummaryArtifact(node, forceRegenerate);
                            outcomes.add(outcome);
                            if ("section".equals(nodeType) && outcome.completed()) {
                                Long chapterId = findChapterIdNeedingRegen(node);
                                if (chapterId != null) chaptersNeedingRegen.add(chapterId);
                            }
                        } finally {
                            semaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })
            ).toList();

            for (Thread t : threads) {
                try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }

        // Regen chapters once after all sections complete — deduped, no blocking the section loop
        for (Long chapterId : chaptersNeedingRegen) {
            DocumentNode chapter = documentNodeRepository.findById(chapterId).orElse(null);
            if (chapter != null) {
                log.info("Post-section chapter regen: chapterId={} (sections now have summaries)", chapterId);
                outcomes.add(enrichSummaryArtifact(chapter, false));
            }
        }

        return outcomes;
    }

    private Long findChapterIdNeedingRegen(DocumentNode sectionNode) {
        DocumentNode lazyParent = sectionNode.getParent();
        if (lazyParent == null || lazyParent.getId() == null) return null;
        DocumentNode parent = documentNodeRepository.findById(lazyParent.getId()).orElse(null);
        if (parent == null || !"chapter".equals(parent.getNodeType())) return null;

        Optional<DocumentNodeArtifact> chapterArtifact = artifactRepository.findLatestCompletedSummaryByNodeId(
                parent.getId(),
                ragProperties.getEnrichment().getPromptVersion(),
                ragProperties.getAi().getChatModel()
        );
        if (chapterArtifact.isEmpty()) return null;
        Map<String, Object> content = chapterArtifact.get().getContentJsonb();
        if (content == null || !(content.get("coverage") instanceof Map<?, ?> coverage)) return null;
        Object fallbackCount = coverage.get("fallbackChildCount");
        int fallback = fallbackCount instanceof Number num ? num.intValue() : 0;
        return fallback > 0 ? parent.getId() : null;
    }

    private ArtifactOutcome enrichSummaryArtifact(DocumentNode node, boolean forceRegenerate) {
        Document document = loadDocumentForNode(node);
        SummaryInput input = resolveSummaryInput(document, node);
        String sourceHash = summarySourceHash(node, input);
        String promptVersion = ragProperties.getEnrichment().getPromptVersion();
        String model = ragProperties.getAi().getChatModel();

        DocumentNodeArtifact existing = artifactRepository
                .findByDocumentNodeIdAndArtifactTypeAndPromptVersionAndModelAndSourceHash(
                        node.getId(),
                        DocumentNodeArtifactType.SUMMARY,
                        promptVersion,
                        model,
                        sourceHash
                )
                .orElse(null);
        if (!forceRegenerate && existing != null && existing.getStatus() == DocumentNodeArtifactStatus.COMPLETED) {
            return ArtifactOutcome.existingCompleted();
        }

        if (input.dependencyError() != null) {
            upsertArtifact(existing, document, node, DocumentNodeArtifactType.SUMMARY, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.SKIPPED, skippedSummaryContent(node, sourceHash, input), input.dependencyError(), null);
            return ArtifactOutcome.generatedSkipped(input.dependencyError());
        }

        DocumentNodeArtifactGenerator generator = findSummaryGenerator(input.summaryMode());
        if (generator == null) {
            upsertArtifact(existing, document, node, DocumentNodeArtifactType.SUMMARY, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.SKIPPED, skippedContent(node, DocumentNodeArtifactType.SUMMARY, sourceHash),
                    PHASE_4_GENERATOR_MISSING, null);
            return ArtifactOutcome.generatedSkipped(PHASE_4_GENERATOR_MISSING);
        }

        try {
            existing = upsertArtifact(existing, document, node, DocumentNodeArtifactType.SUMMARY, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.RUNNING, pendingContent(node, DocumentNodeArtifactType.SUMMARY, sourceHash), null, null);
            SummaryGenerationContext context = new SummaryGenerationContext(
                    document,
                    node,
                    input.summaryMode(),
                    input.directChunks(),
                    input.childSummaries(),
                    input.coverage(),
                    input.fallbackRawChunks()
            );
            DocumentNodeArtifactGenerationResult result = generator.generateSummary(context);
            upsertArtifact(existing, document, node, DocumentNodeArtifactType.SUMMARY, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.COMPLETED, result.contentJsonb(), null, result.tokenCount());
            return ArtifactOutcome.generatedCompleted();
        } catch (BackgroundRateLimitedException ex) {
            log.info("Artifact RATE_LIMITED nodeId={} until={}", node.getId(), ex.getPausedUntil());
            upsertArtifact(existing, document, node, DocumentNodeArtifactType.SUMMARY, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.RATE_LIMITED, rateLimitContent(node, DocumentNodeArtifactType.SUMMARY, sourceHash, ex), ex.getMessage(), null);
            return ArtifactOutcome.generatedFailed();
        } catch (Exception ex) {
            log.warn("Document summary artifact generation failed: documentId={}, nodeId={}, summaryMode={}",
                    document.getId(), node.getId(), input.summaryMode(), ex);
            upsertArtifact(existing, document, node, DocumentNodeArtifactType.SUMMARY, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.FAILED, failureContent(node, DocumentNodeArtifactType.SUMMARY, sourceHash, ex), ex.getMessage(), null);
            return ArtifactOutcome.generatedFailed();
        }
    }

    private SummaryInput resolveSummaryInput(Document document, DocumentNode node) {
        return switch (node.getNodeType()) {
            case "subsection_level2" -> chunksSummaryInput(document, node, SummaryMode.SUBSECTION_LEVEL2_FROM_CHUNKS);
            case "subsection" -> subsectionSummaryInput(document, node);
            case "section" -> sectionSummaryInput(document, node);
            case "chapter" -> parentSummaryInput(document, node, "section", SummaryMode.CHAPTER_FROM_SECTIONS, SummaryMode.CHAPTER_FALLBACK);
            case "part" -> parentSummaryInput(document, node, "chapter", SummaryMode.PART_FROM_CHAPTERS, SummaryMode.PART_FALLBACK);
            default -> chunksSummaryInput(document, node, SummaryMode.CHAPTER_FALLBACK);
        };
    }

    private SummaryInput subsectionSummaryInput(Document document, DocumentNode node) {
        List<DocumentNode> children = directChildrenOfType(node, "subsection_level2");
        if (children.isEmpty()) {
            return chunksSummaryInput(document, node, SummaryMode.SUBSECTION_FROM_CHUNKS);
        }
        return childBackedSummaryInput(
                document,
                node,
                children,
                SummaryMode.SUBSECTION_FROM_LEVEL2_AND_DIRECT_CHUNKS,
                true
        );
    }

    private SummaryInput sectionSummaryInput(Document document, DocumentNode node) {
        List<DocumentNode> subsections = directChildrenOfType(node, "subsection");
        if (subsections.isEmpty()) {
            subsections = directChildrenOfType(node, "subsection_level2");
        }
        if (subsections.isEmpty()) {
            return chunksSummaryInput(document, node, SummaryMode.SECTION_FROM_CHUNKS_FALLBACK);
        }
        return childBackedSummaryInput(
                document,
                node,
                subsections,
                SummaryMode.SECTION_FROM_SUBSECTIONS_AND_DIRECT_CHUNKS,
                true
        );
    }

    private SummaryInput parentSummaryInput(Document document,
                                            DocumentNode node,
                                            String preferredChildType,
                                            SummaryMode preferredMode,
                                            SummaryMode fallbackMode) {
        List<DocumentNode> children = directChildrenOfType(node, preferredChildType);
        if (children.isEmpty()) {
            children = nearestSummaryChildren(node, preferredChildType);
        }
        if (children.isEmpty()) {
            return chunksSummaryInput(document, node, fallbackMode);
        }

        List<ChildSummary> available = new ArrayList<>();
        Map<Long, List<DocumentChunk>> fallbackRawChunks = new LinkedHashMap<>();
        int fallbackChunkLimit = ragProperties.getEnrichment().getRepresentativeSectionChunks();

        for (DocumentNode child : children) {
            Optional<DocumentNodeArtifact> artifact = artifactRepository.findLatestCompletedSummaryByNodeId(
                    child.getId(),
                    ragProperties.getEnrichment().getPromptVersion(),
                    ragProperties.getAi().getChatModel()
            );
            if (artifact.isPresent()) {
                available.add(toChildSummary(child, artifact.get()));
            } else {
                List<DocumentChunk> chunks = directChunks(document, child).stream()
                        .limit(fallbackChunkLimit)
                        .toList();
                if (chunks.isEmpty()) {
                    chunks = limitedChunks(nodeScopeService.getScope(child.getId()).chunks()).stream()
                            .limit(fallbackChunkLimit)
                            .toList();
                }
                fallbackRawChunks.put(child.getId(), chunks);
            }
        }

        SummaryCoverage coverage = new SummaryCoverage(
                children.size(),
                available.size(),
                List.of(),
                0,
                0,
                true,
                fallbackRawChunks.size()
        );
        SummaryMode mode = children.stream().allMatch(child -> preferredChildType.equals(child.getNodeType()))
                ? preferredMode
                : fallbackMode;
        return new SummaryInput(mode, List.of(), available, coverage, null, fallbackRawChunks);
    }

    private SummaryInput childBackedSummaryInput(Document document,
                                                 DocumentNode node,
                                                 List<DocumentNode> children,
                                                 SummaryMode summaryMode,
                                                 boolean includeDirectChunks) {
        ChildSummaryResolution childResolution = childSummaries(children);
        List<DocumentChunk> directChunks = includeDirectChunks
                ? representativeDirectChunks(document, node)
                : List.of();
        SummaryCoverage coverage = new SummaryCoverage(
                children.size(),
                childResolution.childSummaries().size(),
                childResolution.missingNodeIds(),
                directChunks.size(),
                directChunks.size(),
                childResolution.missingNodeIds().isEmpty()
        );
        String dependencyError = childResolution.missingNodeIds().isEmpty()
                ? null
                : "Missing completed child summaries for node ids: " + childResolution.missingNodeIds();
        return new SummaryInput(
                summaryMode,
                directChunks,
                childResolution.childSummaries(),
                coverage,
                dependencyError
        );
    }

    private SummaryInput chunksSummaryInput(Document document, DocumentNode node, SummaryMode summaryMode) {
        List<DocumentChunk> chunks = directChunks(document, node);
        if (chunks.isEmpty()) {
            chunks = limitedChunks(nodeScopeService.getScope(node.getId()).chunks());
        }
        if (chunks.isEmpty()) {
            SummaryCoverage coverage = SummaryCoverage.chunksOnly(0, 0);
            return new SummaryInput(summaryMode, List.of(), List.of(), coverage,
                    "No chunks available for summary node id: " + node.getId());
        }
        SummaryCoverage coverage = SummaryCoverage.chunksOnly(chunks.size(), chunks.size());
        return new SummaryInput(summaryMode, chunks, List.of(), coverage, null);
    }

    private List<DocumentNode> directChildrenOfType(DocumentNode node, String nodeType) {
        if (node == null || node.getId() == null) {
            return List.of();
        }
        return documentNodeRepository.findByParentIdOrderByOrderIndexAsc(node.getId())
                .stream()
                .filter(child -> nodeType.equals(child.getNodeType()))
                .toList();
    }

    private List<DocumentNode> nearestSummaryChildren(DocumentNode node, String preferredChildType) {
        if (node == null || node.getId() == null) {
            return List.of();
        }
        List<String> candidateTypes = "chapter".equals(preferredChildType)
                ? List.of("section", "subsection", "subsection_level2")
                : List.of("subsection", "subsection_level2");
        List<DocumentNode> directChildren = documentNodeRepository.findByParentIdOrderByOrderIndexAsc(node.getId());
        for (String candidateType : candidateTypes) {
            List<DocumentNode> matches = directChildren.stream()
                    .filter(child -> candidateType.equals(child.getNodeType()))
                    .toList();
            if (!matches.isEmpty()) {
                return matches;
            }
        }
        return List.of();
    }

    private ChildSummaryResolution childSummaries(List<DocumentNode> children) {
        List<ChildSummary> childSummaries = new ArrayList<>();
        List<Long> missingNodeIds = new ArrayList<>();
        for (DocumentNode child : children == null ? List.<DocumentNode>of() : children) {
            Optional<DocumentNodeArtifact> artifact = artifactRepository.findLatestCompletedSummaryByNodeId(
                    child.getId(),
                    ragProperties.getEnrichment().getPromptVersion(),
                    ragProperties.getAi().getChatModel()
            );
            if (artifact.isEmpty()) {
                missingNodeIds.add(child.getId());
                continue;
            }
            childSummaries.add(toChildSummary(child, artifact.get()));
        }
        return new ChildSummaryResolution(childSummaries, missingNodeIds);
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

    private List<DocumentChunk> representativeDirectChunks(Document document, DocumentNode node) {
        return directChunks(document, node).stream()
                .limit(ragProperties.getEnrichment().getRepresentativeSectionChunks())
                .toList();
    }

    private List<DocumentChunk> directChunks(Document document, DocumentNode node) {
        if (document == null || document.getId() == null || node == null || node.getId() == null) {
            return List.of();
        }
        return documentChunkRepository.findByDocumentIdAndNodeIdOrderBySourceOrderAsc(document.getId(), node.getId())
                .stream()
                .sorted(Comparator
                        .comparingInt(this::sourceOrder)
                        .thenComparing(chunk -> chunk.getId() == null ? Long.MAX_VALUE : chunk.getId()))
                .limit(ragProperties.getEnrichment().getMaxNodeChunks())
                .toList();
    }

    private ArtifactOutcome enrichArtifact(Long documentNodeId,
                                           DocumentNodeArtifactType artifactType,
                                           boolean forceRegenerate) {
        DocumentNodeScopeService.NodeScope scope = nodeScopeService.getScope(documentNodeId);
        DocumentNode node = scope.rootNode();
        Document document = loadDocumentForNode(node);
        String sourceHash = scope.sourceHash();
        String promptVersion = ragProperties.getEnrichment().getPromptVersion();
        String model = ragProperties.getAi().getChatModel();

        DocumentNodeArtifact existing = artifactRepository
                .findByDocumentNodeIdAndArtifactTypeAndPromptVersionAndModelAndSourceHash(
                        documentNodeId,
                        artifactType,
                        promptVersion,
                        model,
                        sourceHash
                )
                .orElse(null);
        if (!forceRegenerate && existing != null && existing.getStatus() == DocumentNodeArtifactStatus.COMPLETED) {
            return ArtifactOutcome.existingCompleted();
        }

        DocumentNodeArtifactGenerator generator = findGenerator(artifactType);
        if (generator == null) {
            upsertArtifact(existing, document, node, artifactType, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.SKIPPED, skippedContent(node, artifactType, sourceHash), PHASE_4_GENERATOR_MISSING, null);
            return ArtifactOutcome.generatedSkipped(PHASE_4_GENERATOR_MISSING);
        }

        try {
            existing = upsertArtifact(existing, document, node, artifactType, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.RUNNING, pendingContent(node, artifactType, sourceHash), null, null);
            DocumentNodeArtifactGenerationContext context = new DocumentNodeArtifactGenerationContext(
                    document,
                    node,
                    artifactType,
                    limitedChunks(scope.chunks()),
                    sourceHash,
                    promptVersion,
                    model,
                    ragProperties.getEnrichment().getDefaultReviewQuestionMinCount(),
                    ragProperties.getEnrichment().getDefaultReviewQuestionMaxCount(),
                    ragProperties.getEnrichment().getMaxNodeContextChars()
            );
            DocumentNodeArtifactGenerationResult result = generator.generate(context);
            upsertArtifact(existing, document, node, artifactType, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.COMPLETED, result.contentJsonb(), null, result.tokenCount());
            return ArtifactOutcome.generatedCompleted();
        } catch (BackgroundRateLimitedException ex) {
            log.info("Artifact RATE_LIMITED nodeId={} artifactType={} until={}", documentNodeId, artifactType, ex.getPausedUntil());
            upsertArtifact(existing, document, node, artifactType, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.RATE_LIMITED, rateLimitContent(node, artifactType, sourceHash, ex), ex.getMessage(), null);
            return ArtifactOutcome.generatedFailed();
        } catch (Exception ex) {
            log.warn("Document enrichment artifact generation failed: documentId={}, nodeId={}, artifactType={}",
                    document.getId(), documentNodeId, artifactType, ex);
            upsertArtifact(existing, document, node, artifactType, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.FAILED, failureContent(node, artifactType, sourceHash, ex), ex.getMessage(), null);
            return ArtifactOutcome.generatedFailed();
        }
    }

    private Document loadDocumentForNode(DocumentNode node) {
        if (node == null || node.getDocument() == null || node.getDocument().getId() == null) {
            throw new ResourceNotFoundException("Document not found for enrichment node");
        }
        Long documentId = node.getDocument().getId();
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with id: " + documentId));
    }

    private List<DocumentChunk> limitedChunks(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        return chunks.stream()
                .limit(ragProperties.getEnrichment().getMaxNodeChunks())
                .toList();
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

    private String summarySourceHash(DocumentNode node, SummaryInput input) {
        MessageDigest digest = sha256();
        update(digest, "nodeId", node.getId());
        update(digest, "nodeType", node.getNodeType());
        update(digest, "nodeKey", node.getNodeKey());
        update(digest, "sectionPath", node.getSectionPath());
        update(digest, "title", node.getTitle());
        update(digest, "summaryMode", input.summaryMode());
        for (DocumentChunk chunk : input.directChunks()) {
            update(digest, "chunkId", chunk.getId());
            update(digest, "chunkSourceOrder", chunk.getSourceOrder());
            update(digest, "chunkIndex", chunk.getChunkIndex());
            update(digest, "chunkUpdatedAt", chunk.getUpdatedAt());
            update(digest, "chunkContent", chunk.getContent());
        }
        for (ChildSummary childSummary : input.childSummaries()) {
            update(digest, "childNodeId", childSummary.nodeId());
            update(digest, "childArtifactId", childSummary.artifactId());
            update(digest, "childSourceHash", childSummary.sourceHash());
            update(digest, "childSummary", childSummary.summary());
        }
        for (Map.Entry<Long, List<DocumentChunk>> entry : input.fallbackRawChunks().entrySet()) {
            update(digest, "fallbackChildNodeId", entry.getKey());
            for (DocumentChunk chunk : entry.getValue()) {
                update(digest, "fallbackChunkId", chunk.getId());
                update(digest, "fallbackChunkUpdatedAt", chunk.getUpdatedAt());
                update(digest, "fallbackChunkContent", chunk.getContent());
            }
        }
        SummaryCoverage coverage = input.coverage();
        update(digest, "expectedChildCount", coverage.expectedChildCount());
        update(digest, "usedChildCount", coverage.usedChildCount());
        update(digest, "missingChildNodeIds", coverage.missingChildNodeIds());
        update(digest, "directChunkCount", coverage.directChunkCount());
        update(digest, "usedDirectChunkCount", coverage.usedDirectChunkCount());
        update(digest, "complete", coverage.complete());
        update(digest, "fallbackChildCount", coverage.fallbackChildCount());
        return HexFormat.of().formatHex(digest.digest());
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
        digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }

    private DocumentNodeArtifactGenerator findGenerator(DocumentNodeArtifactType artifactType) {
        return artifactGenerators.stream()
                .filter(generator -> generator.supports(artifactType))
                .findFirst()
                .orElse(null);
    }

    private DocumentNodeArtifactGenerator findSummaryGenerator(SummaryMode summaryMode) {
        return artifactGenerators.stream()
                .filter(generator -> generator.supports(DocumentNodeArtifactType.SUMMARY))
                .filter(generator -> generator.supportsSummaryMode(summaryMode))
                .findFirst()
                .orElse(null);
    }

    private DocumentNodeArtifact upsertArtifact(DocumentNodeArtifact existing,
                                                Document document,
                                                DocumentNode node,
                                                DocumentNodeArtifactType artifactType,
                                                String promptVersion,
                                                String model,
                                                String sourceHash,
                                                DocumentNodeArtifactStatus status,
                                                Map<String, Object> content,
                                                String errorMessage,
                                                Integer tokenCount) {
        return transactionTemplate.execute(ignored -> {
            DocumentNodeArtifact artifact = existing == null
                    ? DocumentNodeArtifact.builder()
                    .document(document)
                    .documentNode(node)
                    .artifactType(artifactType)
                    .promptVersion(promptVersion)
                    .model(model)
                    .sourceHash(sourceHash)
                    .build()
                    : artifactRepository.findById(existing.getId()).orElse(existing);
            artifact.setStatus(status);
            artifact.setContentJsonb(content == null ? Map.of() : content);
            artifact.setErrorMessage(errorMessage);
            artifact.setTokenCount(tokenCount);
            return artifactRepository.save(artifact);
        });
    }

    private Map<String, Object> skippedContent(DocumentNode node,
                                               DocumentNodeArtifactType artifactType,
                                               String sourceHash) {
        return skippedContent(node, artifactType, sourceHash, PHASE_4_GENERATOR_MISSING);
    }

    private Map<String, Object> skippedContent(DocumentNode node,
                                               DocumentNodeArtifactType artifactType,
                                               String sourceHash,
                                               String reason) {
        Map<String, Object> content = baseContent(node, artifactType, sourceHash);
        content.put("reason", reason);
        return content;
    }

    private Map<String, Object> skippedSummaryContent(DocumentNode node,
                                                      String sourceHash,
                                                      SummaryInput input) {
        Map<String, Object> content = skippedContent(
                node,
                DocumentNodeArtifactType.SUMMARY,
                sourceHash,
                input.dependencyError()
        );
        content.put("summaryMode", input.summaryMode().name());
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("expectedChildCount", input.coverage().expectedChildCount());
        coverage.put("usedChildCount", input.coverage().usedChildCount());
        coverage.put("missingChildNodeIds", input.coverage().missingChildNodeIds());
        coverage.put("directChunkCount", input.coverage().directChunkCount());
        coverage.put("usedDirectChunkCount", input.coverage().usedDirectChunkCount());
        coverage.put("complete", input.coverage().complete());
        coverage.put("fallbackChildCount", input.coverage().fallbackChildCount());
        content.put("coverage", coverage);
        return content;
    }

    private Map<String, Object> pendingContent(DocumentNode node,
                                               DocumentNodeArtifactType artifactType,
                                               String sourceHash) {
        Map<String, Object> content = baseContent(node, artifactType, sourceHash);
        content.put("reason", "Artifact generation is queued or running");
        return content;
    }

    private Map<String, Object> failureContent(DocumentNode node,
                                               DocumentNodeArtifactType artifactType,
                                               String sourceHash,
                                               Exception ex) {
        Map<String, Object> content = baseContent(node, artifactType, sourceHash);
        content.put("error", ex.getMessage());
        return content;
    }

    private Map<String, Object> rateLimitContent(DocumentNode node,
                                                 DocumentNodeArtifactType artifactType,
                                                 String sourceHash,
                                                 BackgroundRateLimitedException ex) {
        Map<String, Object> content = baseContent(node, artifactType, sourceHash);
        content.put("errorType", "RATE_LIMIT");
        content.put("retryAfter", ex.getPausedUntil() != null ? ex.getPausedUntil().toString() : null);
        return content;
    }

    private Map<String, Object> baseContent(DocumentNode node,
                                            DocumentNodeArtifactType artifactType,
                                            String sourceHash) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("nodeTitle", node.getTitle());
        content.put("sectionPath", node.getSectionPath());
        content.put("artifactType", artifactType.name());
        content.put("sourceHash", sourceHash);
        content.put("generated", false);
        return content;
    }

    private Document markRunning(Long documentId, boolean forceRegenerate) {
        return transactionTemplate.execute(status -> {
            Document document = documentRepository.findById(documentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Document not found with id: " + documentId));
            if (!isEnrichableDocumentStatus(document.getStatus())) {
                return document;
            }
            document.setEnrichmentStatus(DocumentEnrichmentStatus.RUNNING);
            document.setEnrichmentStartedAt(LocalDateTime.now());
            document.setEnrichmentCompletedAt(null);
            document.setEnrichmentError(null);
            if (forceRegenerate && document.getStatus() == DocumentStatus.READY) {
                document.setStatus(DocumentStatus.SUMMARISING);
            }
            return documentRepository.save(document);
        });
    }

    private void markSkipped(Long documentId, String reason) {
        transactionTemplate.executeWithoutResult(status -> documentRepository.findById(documentId).ifPresent(document -> {
            if (document.getStatus() == DocumentStatus.FAILED) {
                return;
            }
            document.setEnrichmentStatus(DocumentEnrichmentStatus.SKIPPED);
            document.setEnrichmentCompletedAt(LocalDateTime.now());
            document.setEnrichmentError(reason);
            documentRepository.save(document);
        }));
    }

    private void finalizeDocumentStatus(Long documentId, List<ArtifactOutcome> outcomes) {
        transactionTemplate.executeWithoutResult(status -> {
            Document document = documentRepository.findById(documentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Document not found with id: " + documentId));
            ArtifactSummary summary = ArtifactSummary.from(outcomes);
            document.setEnrichmentCompletedAt(LocalDateTime.now());
            if (summary.total() == 0 || summary.skipped() == summary.total()) {
                document.setEnrichmentStatus(DocumentEnrichmentStatus.SKIPPED);
                document.setEnrichmentError(summary.primarySkippedReason());
            } else if (summary.failed() == 0 && summary.completed() > 0) {
                document.setStatus(DocumentStatus.READY);
                document.setEnrichmentStatus(DocumentEnrichmentStatus.ENRICHED);
                document.setEnrichmentError(null);
            } else if (summary.failed() == summary.total()) {
                document.setStatus(DocumentStatus.FAILED);
                document.setEnrichmentStatus(DocumentEnrichmentStatus.FAILED);
                document.setEnrichmentError("All enrichment artifacts failed");
            } else {
                document.setStatus(DocumentStatus.READY);
                document.setEnrichmentStatus(DocumentEnrichmentStatus.PARTIAL_FAILED);
                document.setEnrichmentError("Some enrichment artifacts failed or were skipped");
            }
            documentRepository.save(document);
        });
    }

    private boolean isEnrichableDocumentStatus(DocumentStatus status) {
        return status == DocumentStatus.SUMMARISING || status == DocumentStatus.READY;
    }


    private record ArtifactOutcome(boolean completed, boolean failed, boolean skipped, String skippedReason) {
        static ArtifactOutcome generatedCompleted() {
            return new ArtifactOutcome(true, false, false, null);
        }

        static ArtifactOutcome existingCompleted() {
            return new ArtifactOutcome(true, false, false, null);
        }

        static ArtifactOutcome generatedFailed() {
            return new ArtifactOutcome(false, true, false, null);
        }

        static ArtifactOutcome generatedSkipped(String reason) {
            return new ArtifactOutcome(false, false, true, reason);
        }
    }

    private record ArtifactSummary(int total, int completed, int failed, int skipped, List<String> skippedReasons) {
        static ArtifactSummary from(List<ArtifactOutcome> outcomes) {
            List<ArtifactOutcome> safeOutcomes = outcomes == null ? List.of() : outcomes;
            int completed = (int) safeOutcomes.stream().filter(ArtifactOutcome::completed).count();
            int failed = (int) safeOutcomes.stream().filter(ArtifactOutcome::failed).count();
            int skipped = (int) safeOutcomes.stream().filter(ArtifactOutcome::skipped).count();
            List<String> skippedReasons = safeOutcomes.stream()
                    .filter(ArtifactOutcome::skipped)
                    .map(ArtifactOutcome::skippedReason)
                    .filter(reason -> reason != null && !reason.isBlank())
                    .distinct()
                    .toList();
            return new ArtifactSummary(safeOutcomes.size(), completed, failed, skipped, skippedReasons);
        }

        String primarySkippedReason() {
            if (skippedReasons.isEmpty()) {
                return "All enrichment artifacts were skipped";
            }
            if (skippedReasons.size() == 1) {
                return skippedReasons.getFirst();
            }
            return "All enrichment artifacts were skipped: " + skippedReasons;
        }
    }

    private record SummaryInput(
            SummaryMode summaryMode,
            List<DocumentChunk> directChunks,
            List<ChildSummary> childSummaries,
            SummaryCoverage coverage,
            String dependencyError,
            Map<Long, List<DocumentChunk>> fallbackRawChunks
    ) {
        private SummaryInput {
            directChunks = directChunks == null ? List.of() : directChunks;
            childSummaries = childSummaries == null ? List.of() : childSummaries;
            fallbackRawChunks = fallbackRawChunks == null ? Map.of() : fallbackRawChunks;
        }

        SummaryInput(SummaryMode summaryMode,
                     List<DocumentChunk> directChunks,
                     List<ChildSummary> childSummaries,
                     SummaryCoverage coverage,
                     String dependencyError) {
            this(summaryMode, directChunks, childSummaries, coverage, dependencyError, Map.of());
        }
    }

    private record ChildSummaryResolution(List<ChildSummary> childSummaries, List<Long> missingNodeIds) {
        private ChildSummaryResolution {
            childSummaries = childSummaries == null ? List.of() : childSummaries;
            missingNodeIds = missingNodeIds == null ? List.of() : missingNodeIds;
        }
    }

    public enum OnDemandArtifactStatus {
        COMPLETED,
        IN_PROGRESS,
        QUEUED
    }
}
