package com.example.teacherassistantai.service.quiz;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.exception.BackgroundRateLimitedException;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.integration.ai.AiModelRoutingService;
import com.example.teacherassistantai.integration.ai.DigitalOceanAiRateLimiter;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import com.example.teacherassistantai.service.DocumentEnrichmentBacklogService;
import com.example.teacherassistantai.service.DocumentNodeArtifactGenerationContext;
import com.example.teacherassistantai.service.DocumentNodeScopeService;
import com.example.teacherassistantai.service.LlmDocumentNodeArtifactGenerator;
import com.example.teacherassistantai.service.ReviewQuestionCountResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class HierarchicalQuizEnrichmentService {

    private static final Set<String> PHASE_1_EXCLUDED_NODE_TYPES =
            Set.of("chapter", "part", "document", "summary", "review_questions");

    private final DocumentNodeRepository nodeRepository;
    private final DocumentNodeArtifactRepository artifactRepository;
    private final DocumentRepository documentRepository;
    private final DocumentNodeScopeService nodeScopeService;
    private final LlmDocumentNodeArtifactGenerator generator;
    private final QuizGenerationStrategy strategy;
    private final RedisTemplate<String, String> redisTemplate;
    private final RagProperties ragProperties;
    private final TransactionTemplate transactionTemplate;
    private final DigitalOceanAiRateLimiter rateLimiter;
    private final DocumentEnrichmentBacklogService backlogService;
    private final ReviewQuestionCountResolver reviewQuestionCountResolver;
    private final AiModelRoutingService aiModelRoutingService;

    @Async("documentEnrichmentExecutor")
    public void enrichQuizPhase1(Long documentId) {
        if (backlogService.hasSummaryBacklog(documentId)) {
            log.info("BG Quiz Phase 1 deferred because summary backlog exists: documentId={}", documentId);
            return;
        }
        List<DocumentNode> smallNodes = getSmallNodes(documentId);
        log.info("BG Quiz Phase 1: {} small nodes for documentId={}", smallNodes.size(), documentId);
        enrichBatch(smallNodes, QuizGenerationStrategy.QuizInputType.RAW_CHUNKS);
    }

    @Async("documentEnrichmentExecutor")
    public void enrichQuizPhase2And3(Long documentId) {
        List<DocumentNode> largeNodes = getLargeNonChapterNodes(documentId);
        log.info("BG Quiz Phase 2: {} large non-chapter nodes for documentId={}", largeNodes.size(), documentId);
        enrichBatch(largeNodes, QuizGenerationStrategy.QuizInputType.CHILD_SUMMARIES);

        List<DocumentNode> chapters = getChapterNodes(documentId);
        log.info("BG Quiz Phase 3: {} chapter nodes for documentId={}", chapters.size(), documentId);
        enrichBatch(chapters, QuizGenerationStrategy.QuizInputType.CHILD_SUMMARIES);
    }

    @Async("documentEnrichmentExecutor")
    public void enqueueChapterQuizGeneration(Long chapterNodeId) {
        DocumentNode chapter = nodeRepository.findById(chapterNodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Document node not found with id: " + chapterNodeId));
        if (!"chapter".equalsIgnoreCase(String.valueOf(chapter.getNodeType()))) {
            log.info("BG Quiz: skipping enqueue for non-chapter nodeId={} type={}", chapter.getId(), chapter.getNodeType());
            return;
        }
        Long documentId = chapter.getDocument() == null ? null : chapter.getDocument().getId();
        if (documentId != null && backlogService.hasSummaryBacklog(documentId)) {
            log.info("BG Quiz: chapter enqueue deferred because summary backlog exists: nodeId={}", chapter.getId());
            return;
        }
        if (rateLimiter.isBackgroundPaused()) {
            log.info("BG Quiz: chapter enqueue deferred because background is paused: nodeId={}", chapter.getId());
            return;
        }
        DocumentNodeScopeService.NodeScope scope = nodeScopeService.getScope(chapter.getId());
        QuizGenerationStrategy.QuizInputType inputType = strategy.determine(chapter, scope.chunks().size());
        enrichQuizWithLock(chapter, inputType);
    }

    private void enrichBatch(List<DocumentNode> nodes, QuizGenerationStrategy.QuizInputType inputType) {
        if (nodes.isEmpty()) return;
        int concurrency = ragProperties.getEnrichment().getMaxConcurrency();
        Semaphore semaphore = new Semaphore(concurrency);
        AtomicBoolean stopBatch = new AtomicBoolean(false);

        List<Thread> threads = new ArrayList<>();
        for (DocumentNode node : nodes) {
            if (stopBatch.get()) {
                break;
            }
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (stopBatch.get()) {
                semaphore.release();
                break;
            }
            Thread thread = Thread.ofVirtual().start(() -> {
                try {
                    QuizArtifactOutcome outcome = enrichQuizWithLock(node, inputType);
                    if (outcome.rateLimited()) {
                        stopBatch.set(true);
                        log.info("BG Quiz: stopping batch because rate limit was reached at nodeId={}", node.getId());
                    }
                } finally {
                    semaphore.release();
                }
            });
            threads.add(thread);
        }

        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private QuizArtifactOutcome enrichQuizWithLock(DocumentNode node, QuizGenerationStrategy.QuizInputType inputType) {
        if (rateLimiter.isBackgroundPaused()) {
            log.info("BG Quiz: background paused, stopping before lock acquire for nodeId={}", node.getId());
            return QuizArtifactOutcome.RATE_LIMITED;
        }
        String lock = "artifact-lock:" + node.getId() + ":REVIEW_QUESTION_SET";
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(lock, "bg", Duration.ofMinutes(5)))) {
            log.debug("BG Quiz: skipping nodeId={} — lock held", node.getId());
            return QuizArtifactOutcome.SKIPPED;
        }
        try {
            if (artifactCompleted(node.getId())) {
                log.debug("BG Quiz: skipping nodeId={} — already COMPLETED", node.getId());
                return QuizArtifactOutcome.COMPLETED;
            }
            return generateAndSaveQuizArtifactInternal(node, inputType);
        } finally {
            redisTemplate.delete(lock);
        }
    }

    public QuizArtifactOutcome generateAndSaveQuizArtifact(DocumentNode node, QuizGenerationStrategy.QuizInputType inputType) {
        return generateAndSaveQuizArtifactInternal(node, inputType);
    }

    private QuizArtifactOutcome generateAndSaveQuizArtifactInternal(DocumentNode node, QuizGenerationStrategy.QuizInputType inputType) {
        DocumentNodeScopeService.NodeScope scope = nodeScopeService.getScope(node.getId());
        Document document = loadDocument(node);
        String sourceHash = scope.sourceHash();
        String promptVersion = ragProperties.getEnrichment().getPromptVersion();
        String model = aiModelRoutingService.enrichmentModelFor(DocumentNodeArtifactType.REVIEW_QUESTION_SET);

        DocumentNodeArtifact existing = artifactRepository
                .findByDocumentNodeIdAndArtifactTypeAndPromptVersionAndModelAndSourceHash(
                        node.getId(), DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                        promptVersion, model, sourceHash)
                .orElse(null);
        if (existing != null && existing.getStatus() == DocumentNodeArtifactStatus.COMPLETED) {
            return QuizArtifactOutcome.COMPLETED;
        }

        List<DocumentChunk> chunks = selectChunks(node, inputType, scope);
        if (chunks.isEmpty()) {
            log.warn("BG Quiz: no chunks for nodeId={}, skipping", node.getId());
            return QuizArtifactOutcome.SKIPPED;
        }

        existing = upsert(existing, document, node, promptVersion, model, sourceHash,
                DocumentNodeArtifactStatus.RUNNING, Map.of(), null, null);

        try {
            ReviewQuestionCountResolver.CountRange questionCountRange = reviewQuestionCountResolver.resolve(node.getNodeType());
            DocumentNodeArtifactGenerationContext context = new DocumentNodeArtifactGenerationContext(
                    document, node, DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                    chunks, sourceHash, promptVersion, model,
                    questionCountRange.min(),
                    questionCountRange.max(),
                    ragProperties.getEnrichment().getMaxNodeContextChars()
            );
            var result = generator.generate(context);
            upsert(existing, document, node, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.COMPLETED, result.contentJsonb(), null, result.tokenCount());
            log.info("BG Quiz: COMPLETED nodeId={} inputType={}", node.getId(), inputType);
            return QuizArtifactOutcome.COMPLETED;
        } catch (BackgroundRateLimitedException ex) {
            log.info("Artifact RATE_LIMITED nodeId={} until={}", node.getId(), ex.getPausedUntil());
            Map<String, Object> content = new java.util.LinkedHashMap<>();
            content.put("errorType", "RATE_LIMIT");
            content.put("retryAfter", ex.getPausedUntil() != null ? ex.getPausedUntil().toString() : null);
            upsert(existing, document, node, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.RATE_LIMITED, content, ex.getMessage(), null);
            return QuizArtifactOutcome.RATE_LIMITED;
        } catch (Exception ex) {
            log.warn("BG Quiz: generation failed for nodeId={}", node.getId(), ex);
            upsert(existing, document, node, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.FAILED, Map.of("error", String.valueOf(ex.getMessage())),
                    ex.getMessage(), null);
            return QuizArtifactOutcome.FAILED;
        }
    }

    private List<DocumentChunk> selectChunks(DocumentNode node,
                                              QuizGenerationStrategy.QuizInputType inputType,
                                              DocumentNodeScopeService.NodeScope scope) {
        if (inputType == QuizGenerationStrategy.QuizInputType.RAW_CHUNKS) {
            return limitedChunks(scope.chunks());
        }
        List<DocumentNode> children = nodeRepository.findByParentIdOrderByOrderIndexAsc(node.getId());
        if (children.isEmpty()) {
            return limitedChunks(scope.chunks());
        }
        int perChild = Math.max(1, ragProperties.getEnrichment().getRepresentativeSectionChunks());
        List<DocumentChunk> result = new ArrayList<>();
        for (DocumentNode child : children) {
            List<DocumentChunk> childChunks = nodeScopeService.getScope(child.getId()).chunks();
            result.addAll(childChunks.stream().limit(perChild).toList());
        }
        return result.stream().limit(ragProperties.getEnrichment().getMaxNodeChunks()).toList();
    }

    private DocumentNodeArtifact upsert(DocumentNodeArtifact existing,
                                         Document document, DocumentNode node,
                                         String promptVersion, String model, String sourceHash,
                                         DocumentNodeArtifactStatus status,
                                         Map<String, Object> content,
                                         String errorMessage, Integer tokenCount) {
        return transactionTemplate.execute(ignored -> {
            DocumentNodeArtifact artifact = existing == null
                    ? DocumentNodeArtifact.builder()
                            .document(document)
                            .documentNode(node)
                            .artifactType(DocumentNodeArtifactType.REVIEW_QUESTION_SET)
                            .promptVersion(promptVersion)
                            .model(model)
                            .sourceHash(sourceHash)
                            .build()
                    : artifactRepository.findById(existing.getId()).orElse(existing);
            artifact.setStatus(status);
            artifact.setContentJsonb(content);
            artifact.setErrorMessage(errorMessage);
            artifact.setTokenCount(tokenCount);
            return artifactRepository.save(artifact);
        });
    }

    private boolean artifactCompleted(Long nodeId) {
        return artifactRepository.findLatestByNodeTypeAndStatus(
                nodeId, DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                DocumentNodeArtifactStatus.COMPLETED
        ).stream().findFirst().isPresent();
    }

    private Document loadDocument(DocumentNode node) {
        if (node.getDocument() == null || node.getDocument().getId() == null) {
            throw new ResourceNotFoundException("Document not found for quiz node id: " + node.getId());
        }
        return documentRepository.findById(node.getDocument().getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document not found with id: " + node.getDocument().getId()));
    }

    private List<DocumentChunk> limitedChunks(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return List.of();
        return chunks.stream().limit(ragProperties.getEnrichment().getMaxNodeChunks()).toList();
    }

    private List<DocumentNode> getSmallNodes(Long documentId) {
        return nodeRepository.findByDocumentIdOrderByOrderIndexAsc(documentId).stream()
                .filter(n -> !PHASE_1_EXCLUDED_NODE_TYPES.contains(n.getNodeType()))
                .filter(n -> {
                    int count = nodeScopeService.getScope(n.getId()).chunks().size();
                    return strategy.determine(n, count) == QuizGenerationStrategy.QuizInputType.RAW_CHUNKS;
                })
                .toList();
    }

    private List<DocumentNode> getLargeNonChapterNodes(Long documentId) {
        return nodeRepository.findByDocumentIdOrderByOrderIndexAsc(documentId).stream()
                .filter(n -> !PHASE_1_EXCLUDED_NODE_TYPES.contains(n.getNodeType()))
                .filter(n -> {
                    int count = nodeScopeService.getScope(n.getId()).chunks().size();
                    return strategy.determine(n, count) == QuizGenerationStrategy.QuizInputType.CHILD_SUMMARIES;
                })
                .toList();
    }

    private List<DocumentNode> getChapterNodes(Long documentId) {
        return nodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(documentId, "chapter");
    }

    public enum QuizArtifactOutcome {
        COMPLETED,
        SKIPPED,
        RATE_LIMITED,
        FAILED;

        boolean rateLimited() {
            return this == RATE_LIMITED;
        }
    }
}
