package com.example.teacherassistantai.service.quiz;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.exception.AiRateLimitedException;
import com.example.teacherassistantai.exception.BackgroundRateLimitedException;
import com.example.teacherassistantai.exception.BackgroundTransientAiException;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.integration.ai.AiModelRoutingService;
import com.example.teacherassistantai.integration.ai.AiWorkload;
import com.example.teacherassistantai.integration.ai.DigitalOceanAiRateLimiter;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import com.example.teacherassistantai.service.DocumentEnrichmentBacklogService;
import com.example.teacherassistantai.service.DocumentNodeArtifactGenerationContext;
import com.example.teacherassistantai.service.DocumentNodeScopeService;
import com.example.teacherassistantai.service.LlmDocumentNodeArtifactGenerator;
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
    private final AiModelRoutingService aiModelRoutingService;
    private final ReviewQuestionInputResolver inputResolver;

    @Async("documentEnrichmentExecutor")
    public void enrichQuizPhase1(Long documentId) {
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
    public void enqueueChapterQuizGeneration(Long chapterNodeId, boolean onDemand) {
        DocumentNode chapter = nodeRepository.findById(chapterNodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Document node not found with id: " + chapterNodeId));
        if (!"chapter".equalsIgnoreCase(String.valueOf(chapter.getNodeType()))) {
            log.info("BG Quiz: skipping enqueue for non-chapter nodeId={} type={}", chapter.getId(), chapter.getNodeType());
            return;
        }
        Long documentId = chapter.getDocument() == null ? null : chapter.getDocument().getId();
        if (documentId != null && backlogService.hasSummaryBacklog(documentId)) {
            log.debug("BG Quiz: summary backlog exists but review question enrichment remains independent: nodeId={}",
                    chapter.getId());
        }
        if (!onDemand && rateLimiter.isPaused(AiWorkload.ENRICH_REVIEW_QUESTION)) {
            log.info("BG Quiz: chapter enqueue deferred because review question enrichment is paused: nodeId={}", chapter.getId());
            return;
        }
        DocumentNodeScopeService.NodeScope scope = nodeScopeService.getScope(chapter.getId());
        QuizGenerationStrategy.QuizInputType inputType = strategy.determine(chapter, scope.chunks().size());
        if (onDemand) {
            enrichQuizWithLockOnDemand(chapter, inputType);
        } else {
            enrichQuizWithLock(chapter, inputType);
        }
    }

    private QuizArtifactOutcome enrichQuizWithLockOnDemand(DocumentNode node, QuizGenerationStrategy.QuizInputType inputType) {
        String lock = "artifact-lock:" + node.getId() + ":REVIEW_QUESTION_SET";
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(lock, "od", Duration.ofMinutes(5)))) {
            log.debug("On-demand Quiz: skipping nodeId={} — lock held", node.getId());
            return QuizArtifactOutcome.SKIPPED;
        }
        try {
            if (artifactCompleted(node.getId())) {
                return QuizArtifactOutcome.COMPLETED;
            }
            return generateAndSaveQuizArtifactInternal(node, inputType, false, true);
        } finally {
            redisTemplate.delete(lock);
        }
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
        if (rateLimiter.isPaused(AiWorkload.ENRICH_REVIEW_QUESTION)) {
            log.info("BG Quiz: review question enrichment paused, stopping before lock acquire for nodeId={}", node.getId());
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
            return generateAndSaveQuizArtifactInternal(node, inputType, false, false);
        } finally {
            redisTemplate.delete(lock);
        }
    }

    public QuizArtifactOutcome generateAndSaveQuizArtifact(DocumentNode node, QuizGenerationStrategy.QuizInputType inputType) {
        return generateAndSaveQuizArtifactInternal(node, inputType, false, false);
    }

    public QuizArtifactOutcome generateAndSaveQuizArtifact(DocumentNode node,
                                                           QuizGenerationStrategy.QuizInputType inputType,
                                                           boolean forceRegenerate) {
        return generateAndSaveQuizArtifactInternal(node, inputType, forceRegenerate, false);
    }

    public QuizArtifactOutcome generateAndSaveQuizArtifactOnDemand(DocumentNode node,
                                                                   QuizGenerationStrategy.QuizInputType inputType) {
        return generateAndSaveQuizArtifactInternal(node, inputType, false, true);
    }

    private QuizArtifactOutcome generateAndSaveQuizArtifactInternal(DocumentNode node,
                                                                    QuizGenerationStrategy.QuizInputType inputType,
                                                                    boolean forceRegenerate,
                                                                    boolean isOnDemand) {
        ReviewQuestionGenerationContext reviewContext = inputResolver.resolve(node);
        Document document = loadDocument(node);
        String sourceHash = reviewContext.sourceHash();
        String promptVersion = ragProperties.getEnrichment().getPromptVersion();
        String model = aiModelRoutingService.enrichmentModelFor(DocumentNodeArtifactType.REVIEW_QUESTION_SET);

        DocumentNodeArtifact existing = artifactRepository
                .findByDocumentNodeIdAndArtifactTypeAndPromptVersionAndModelAndSourceHash(
                        node.getId(), DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                        promptVersion, model, sourceHash)
                .orElse(null);
        if (!forceRegenerate && existing != null && existing.getStatus() == DocumentNodeArtifactStatus.COMPLETED) {
            return QuizArtifactOutcome.COMPLETED;
        }

        if (!reviewContext.hasUsableContext()) {
            log.warn("BG Quiz: no usable review question context for nodeId={}, skipping", node.getId());
            return QuizArtifactOutcome.SKIPPED;
        }

        existing = upsert(existing, document, node, promptVersion, model, sourceHash,
                DocumentNodeArtifactStatus.RUNNING, Map.of(), null, null);

        AiWorkload workloadOverride = isOnDemand ? AiWorkload.ENRICH_REVIEW_QUESTION_ONDEMAND : null;
        try {
            DocumentNodeArtifactGenerationContext context = new DocumentNodeArtifactGenerationContext(
                    document,
                    node,
                    DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                    reviewContext.allowedCitationChunks(),
                    sourceHash,
                    promptVersion,
                    model,
                    reviewContext.minQuestionCount(),
                    reviewContext.maxQuestionCount(),
                    ragProperties.getEnrichment().getMaxNodeContextChars(),
                    reviewContext,
                    workloadOverride
            );
            var result = generator.generate(context);
            upsert(existing, document, node, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.COMPLETED, result.contentJsonb(), null, result.tokenCount());
            log.info("BG Quiz: COMPLETED nodeId={} inputType={} isOnDemand={}", node.getId(), inputType, isOnDemand);
            return QuizArtifactOutcome.COMPLETED;
        } catch (BackgroundRateLimitedException ex) {
            log.info("Artifact RATE_LIMITED nodeId={} until={}", node.getId(), ex.getPausedUntil());
            Map<String, Object> content = new java.util.LinkedHashMap<>();
            content.put("errorType", "RATE_LIMIT");
            content.put("retryAfter", ex.getPausedUntil() != null ? ex.getPausedUntil().toString() : null);
            upsert(existing, document, node, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.RATE_LIMITED, content, ex.getMessage(), null);
            return QuizArtifactOutcome.RATE_LIMITED;
        } catch (AiRateLimitedException ex) {
            log.info("Artifact RATE_LIMITED (ondemand) nodeId={}", node.getId());
            Map<String, Object> content = new java.util.LinkedHashMap<>();
            content.put("errorType", "RATE_LIMIT");
            upsert(existing, document, node, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.RATE_LIMITED, content, ex.getMessage(), null);
            return QuizArtifactOutcome.RATE_LIMITED;
        } catch (BackgroundTransientAiException ex) {
            log.info("Artifact retryable transient AI error nodeId={} errorType={}", node.getId(), ex.getErrorType());
            upsert(existing, document, node, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.RATE_LIMITED, transientContent(ex), ex.getMessage(), null);
            return QuizArtifactOutcome.RATE_LIMITED;
        } catch (Exception ex) {
            if (isRedisFactoryStoppedError(ex)) {
                log.info("BG Quiz: Redis factory stopped during generation, marking RATE_LIMITED for retry: nodeId={}", node.getId());
                Map<String, Object> content = new java.util.LinkedHashMap<>();
                content.put("errorType", "REDIS_UNAVAILABLE");
                content.put("error", ex.getMessage());
                upsert(existing, document, node, promptVersion, model, sourceHash,
                        DocumentNodeArtifactStatus.RATE_LIMITED, content, ex.getMessage(), null);
                return QuizArtifactOutcome.RATE_LIMITED;
            }
            log.warn("BG Quiz: generation failed for nodeId={}", node.getId(), ex);
            upsert(existing, document, node, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.FAILED, failureContent(ex),
                    ex.getMessage(), null);
            return QuizArtifactOutcome.FAILED;
        }
    }

    private boolean isRedisFactoryStoppedError(Throwable ex) {
        Throwable cause = ex;
        for (int depth = 0; cause != null && depth < 5; depth++) {
            String msg = cause.getMessage();
            if (msg != null && msg.contains("STOPPED")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private Map<String, Object> failureContent(Exception ex) {
        Map<String, Object> content = new java.util.LinkedHashMap<>();
        String message = ex == null ? null : ex.getMessage();
        if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("json")) {
            content.put("errorType", "INVALID_JSON");
        }
        content.put("error", String.valueOf(message));
        return content;
    }

    private Map<String, Object> transientContent(BackgroundTransientAiException ex) {
        Map<String, Object> content = new java.util.LinkedHashMap<>();
        content.put("errorType", ex.getErrorType() == null ? "TRANSIENT_AI_ERROR" : ex.getErrorType());
        content.put("retryAfter", ex.getRetryAfter() == null ? null : ex.getRetryAfter().toString());
        content.put("message", ex.getMessage());
        return content;
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
