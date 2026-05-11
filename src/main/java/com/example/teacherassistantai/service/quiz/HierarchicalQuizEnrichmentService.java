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
import com.example.teacherassistantai.integration.ai.DigitalOceanAiRateLimiter;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
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
import java.util.concurrent.Semaphore;

@Slf4j
@Service
@RequiredArgsConstructor
public class HierarchicalQuizEnrichmentService {

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

    private void enrichBatch(List<DocumentNode> nodes, QuizGenerationStrategy.QuizInputType inputType) {
        if (nodes.isEmpty()) return;
        int concurrency = ragProperties.getEnrichment().getMaxConcurrency();
        Semaphore semaphore = new Semaphore(concurrency);

        List<Thread> threads = nodes.stream().map(node ->
            Thread.ofVirtual().start(() -> {
                try {
                    semaphore.acquire();
                    try {
                        enrichQuizWithLock(node, inputType);
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            })
        ).toList();

        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void enrichQuizWithLock(DocumentNode node, QuizGenerationStrategy.QuizInputType inputType) {
        if (rateLimiter.isBackgroundPaused()) {
            log.info("BG Quiz: background paused, skipping lock acquire for nodeId={}", node.getId());
            return;
        }
        String lock = "artifact-lock:" + node.getId() + ":REVIEW_QUESTION_SET";
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(lock, "bg", Duration.ofMinutes(5)))) {
            log.debug("BG Quiz: skipping nodeId={} — lock held", node.getId());
            return;
        }
        try {
            if (artifactCompleted(node.getId())) {
                log.debug("BG Quiz: skipping nodeId={} — already COMPLETED", node.getId());
                return;
            }
            generateAndSaveQuizArtifact(node, inputType);
        } finally {
            redisTemplate.delete(lock);
        }
    }

    public void generateAndSaveQuizArtifact(DocumentNode node, QuizGenerationStrategy.QuizInputType inputType) {
        DocumentNodeScopeService.NodeScope scope = nodeScopeService.getScope(node.getId());
        Document document = loadDocument(node);
        String sourceHash = scope.sourceHash();
        String promptVersion = ragProperties.getEnrichment().getPromptVersion();
        String model = ragProperties.getAi().getChatModel();

        DocumentNodeArtifact existing = artifactRepository
                .findByDocumentNodeIdAndArtifactTypeAndPromptVersionAndModelAndSourceHash(
                        node.getId(), DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                        promptVersion, model, sourceHash)
                .orElse(null);
        if (existing != null && existing.getStatus() == DocumentNodeArtifactStatus.COMPLETED) {
            return;
        }

        List<DocumentChunk> chunks = selectChunks(node, inputType, scope);
        if (chunks.isEmpty()) {
            log.warn("BG Quiz: no chunks for nodeId={}, skipping", node.getId());
            return;
        }

        existing = upsert(existing, document, node, promptVersion, model, sourceHash,
                DocumentNodeArtifactStatus.RUNNING, Map.of(), null, null);

        try {
            DocumentNodeArtifactGenerationContext context = new DocumentNodeArtifactGenerationContext(
                    document, node, DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                    chunks, sourceHash, promptVersion, model,
                    ragProperties.getEnrichment().getDefaultReviewQuestionMinCount(),
                    ragProperties.getEnrichment().getDefaultReviewQuestionMaxCount(),
                    ragProperties.getEnrichment().getMaxNodeContextChars()
            );
            var result = generator.generate(context);
            upsert(existing, document, node, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.COMPLETED, result.contentJsonb(), null, result.tokenCount());
            log.info("BG Quiz: COMPLETED nodeId={} inputType={}", node.getId(), inputType);
        } catch (BackgroundRateLimitedException ex) {
            log.info("Artifact RATE_LIMITED nodeId={} until={}", node.getId(), ex.getPausedUntil());
            Map<String, Object> content = new java.util.LinkedHashMap<>();
            content.put("errorType", "RATE_LIMIT");
            content.put("retryAfter", ex.getPausedUntil() != null ? ex.getPausedUntil().toString() : null);
            upsert(existing, document, node, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.RATE_LIMITED, content, ex.getMessage(), null);
        } catch (Exception ex) {
            log.warn("BG Quiz: generation failed for nodeId={}", node.getId(), ex);
            upsert(existing, document, node, promptVersion, model, sourceHash,
                    DocumentNodeArtifactStatus.FAILED, Map.of("error", String.valueOf(ex.getMessage())),
                    ex.getMessage(), null);
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
                .filter(n -> !List.of("chapter", "part").contains(n.getNodeType()))
                .filter(n -> {
                    int count = nodeScopeService.getScope(n.getId()).chunks().size();
                    return strategy.determine(n, count) == QuizGenerationStrategy.QuizInputType.RAW_CHUNKS;
                })
                .toList();
    }

    private List<DocumentNode> getLargeNonChapterNodes(Long documentId) {
        return nodeRepository.findByDocumentIdOrderByOrderIndexAsc(documentId).stream()
                .filter(n -> !List.of("chapter", "part").contains(n.getNodeType()))
                .filter(n -> {
                    int count = nodeScopeService.getScope(n.getId()).chunks().size();
                    return strategy.determine(n, count) == QuizGenerationStrategy.QuizInputType.CHILD_SUMMARIES;
                })
                .toList();
    }

    private List<DocumentNode> getChapterNodes(Long documentId) {
        return nodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(documentId, "chapter");
    }
}
