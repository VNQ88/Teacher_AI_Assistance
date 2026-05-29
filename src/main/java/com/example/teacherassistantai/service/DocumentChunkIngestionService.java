package com.example.teacherassistantai.service;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.integration.ai.AiEmbeddingGateway;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentChunkIngestionService {

    private final MarkdownChunkingService markdownChunkingService;
    private final ChunkMetadataBuilder chunkMetadataBuilder;
    private final AiEmbeddingGateway embeddingGateway;
    private final DocumentChunkRepository documentChunkRepository;
    private final RagProperties ragProperties;
    private final PlatformTransactionManager transactionManager;

    public List<DocumentChunk> ingest(Document document, String markdown) {
        MarkdownChunkingService.HierarchicalMarkdownDocument hierarchyDocument =
                markdownChunkingService.parseHierarchicalDocument(markdown, document.getTitle());
        return ingest(document, hierarchyDocument, Map.of());
    }

    // No @Transactional — transactions managed per-step to avoid holding a connection during HTTP calls
    public List<DocumentChunk> ingest(Document document,
                                      MarkdownChunkingService.HierarchicalMarkdownDocument hierarchyDocument,
                                      Map<String, DocumentNode> nodeByKey) {
        List<HierarchicalMarkdownChunk> rawChunks = hierarchyDocument.chunks();
        List<DocumentChunk> shells = buildChunkShells(rawChunks, document, nodeByKey);

        // Step 1: bulk save all chunk shells (no embeddings yet) — one short transaction
        List<DocumentChunk> saved = saveAllChunkShells(shells);
        log.info("Saved {} chunk shells for documentId={}", saved.size(), document.getId());

        // Step 2: embed in parallel batches — DB connection NOT held during HTTP calls
        embedInParallelBatches(saved, document.getId());

        return saved;
    }

    @Transactional
    public void deleteExistingChunks(Long documentId) {
        documentChunkRepository.deleteMessageSourceLinksByDocumentId(documentId);
        documentChunkRepository.deleteByDocumentId(documentId);
    }

    @Transactional
    protected List<DocumentChunk> saveAllChunkShells(List<DocumentChunk> shells) {
        List<DocumentChunk> saved = new ArrayList<>(shells.size());
        for (DocumentChunk shell : shells) {
            saved.add(documentChunkRepository.save(shell));
        }
        return saved;
    }

    private void embedInParallelBatches(List<DocumentChunk> chunks, Long documentId) {
        int batchSize = ragProperties.getEmbeddingBatchSize();
        int concurrency = ragProperties.getEmbeddingConcurrency();
        List<List<DocumentChunk>> batches = partition(chunks, batchSize);
        int totalBatches = batches.size();
        AtomicInteger batchesDone = new AtomicInteger(0);
        AtomicInteger chunksDone = new AtomicInteger(0);
        int total = chunks.size();

        log.info("Starting parallel batch embedding: documentId={}, chunks={}, batchSize={}, concurrency={}, batches={}",
                documentId, total, batchSize, concurrency, totalBatches);

        // TransactionTemplate with REQUIRES_NEW — each batch commits independently
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Semaphore semaphore = new Semaphore(concurrency);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Void>> futures = batches.stream()
                    .<Future<Void>>map(batch -> executor.submit(() -> {
                        semaphore.acquireUninterruptibly();
                        try {
                            // HTTP call outside transaction — no connection held during network wait
                            List<String> texts = batch.stream().map(DocumentChunk::getEmbedText).toList();
                            if (log.isDebugEnabled() && !texts.isEmpty()) {
                                String sample = texts.getFirst();
                                log.debug("First chunk embedText sample (first 100 chars): {}",
                                        sample.substring(0, Math.min(100, sample.length())));
                            }
                            List<List<Double>> embeddings = embeddingGateway.embedAll(texts);

                            if (embeddings.size() != batch.size()) {
                                throw new InvalidDataException(
                                        "Embedding count mismatch: expected %d, got %d"
                                                .formatted(batch.size(), embeddings.size()));
                            }

                            // DB update in its own short transaction
                            txTemplate.executeWithoutResult(status -> {
                                for (int i = 0; i < batch.size(); i++) {
                                    List<Double> embedding = embeddings.get(i);
                                    validateEmbeddingDimensions(embedding, batch.get(i).getId());
                                    documentChunkRepository.updateEmbeddingLiteral(
                                            batch.get(i).getId(), toVectorLiteral(embedding));
                                }
                            });

                            int batchNo = batchesDone.incrementAndGet();
                            int chunksNow = chunksDone.addAndGet(batch.size());
                            log.info("Embedding progress: {}/{} batches, {}/{} chunks (documentId={})",
                                    batchNo, totalBatches, chunksNow, total, documentId);
                        } finally {
                            semaphore.release();
                        }
                        return null;
                    }))
                    .toList();

            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException ex) {
                    throw new RuntimeException("Batch embedding failed for documentId=" + documentId, ex.getCause());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Batch embedding interrupted for documentId=" + documentId, ex);
                }
            }
        }

        log.info("Completed embedding: documentId={}, total={} chunks", documentId, total);
    }

    private List<DocumentChunk> buildChunkShells(List<HierarchicalMarkdownChunk> rawChunks,
                                                  Document document,
                                                  Map<String, DocumentNode> nodeByKey) {
        List<DocumentChunk> shells = new ArrayList<>(rawChunks.size());
        for (int i = 0; i < rawChunks.size(); i++) {
            HierarchicalMarkdownChunk hc = rawChunks.get(i);
            String content = hc.content();
            String embedText = hc.embedText();
            DocumentNode node = resolveNode(nodeByKey, hc.nodeId(), "nodeId");
            DocumentNode parentNode = resolveNode(nodeByKey, hc.parentNodeId(), "parentNodeId");
            shells.add(DocumentChunk.builder()
                    .document(document)
                    .subjectId(document.getSubject().getId())
                    .node(node)
                    .parentNode(parentNode)
                    .chunkIndex(i + 1)
                    .sourceOrder(i + 1)
                    .chunkType(hc.chunkType())
                    .sectionPath(String.join(" > ", hc.breadcrumb()))
                    .pageFrom(hc.pageFrom())
                    .pageTo(hc.pageTo())
                    .content(content)
                    .embedText(embedText)
                    .tokenCount(Math.max(1, content.length() / 4))
                    .metadataJsonb(chunkMetadataBuilder.buildHierarchicalJsonb(
                            hc.pageFrom(), hc.pageTo(), hc.sectionHeader(), hc.chunkType(),
                            content.length(), hc.nodeType(), hc.nodeId(), hc.parentNodeId(),
                            hc.breadcrumb(), hc.charStart(), hc.charEnd()))
                    .build());
        }
        return shells;
    }

    private DocumentNode resolveNode(Map<String, DocumentNode> nodeByKey, String nodeKey, String fieldName) {
        if (nodeKey == null || nodeKey.isBlank()) return null;
        if (nodeByKey == null || nodeByKey.isEmpty()) return null;
        DocumentNode node = nodeByKey.get(nodeKey);
        if (node == null) {
            throw new InvalidDataException("Cannot resolve %s '%s' to persisted document_nodes row"
                    .formatted(fieldName, nodeKey));
        }
        return node;
    }

    private void validateEmbeddingDimensions(List<Double> embedding, Long chunkId) {
        int actual = embedding == null ? 0 : embedding.size();
        int expected = ragProperties.getEmbeddingDimensions();
        if (actual != expected) {
            throw new InvalidDataException("Embedding dimension mismatch for chunk %d: expected %d, got %d"
                    .formatted(chunkId, expected, actual));
        }
    }

    public String toVectorLiteral(List<Double> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) builder.append(',');
            builder.append(values.get(i));
        }
        builder.append(']');
        return builder.toString();
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
