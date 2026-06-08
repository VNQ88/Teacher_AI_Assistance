package com.example.teacherassistantai.service;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.dto.response.RagDebugRetrieveResponse;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.integration.ai.AiEmbeddingGateway;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class VectorRetrievalService {

    private final AiEmbeddingGateway embeddingGateway;
    private final DocumentChunkRepository documentChunkRepository;
    private final RagProperties ragProperties;
    private final LocalRerankingService localRerankingService;
    private final RagScopeResolverService ragScopeResolverService;
    private final CoarseNodeSearchService coarseNodeSearchService;

    public List<DocumentChunk> retrieve(ChatSession session, String question, int topK) {
        return retrieveTrace(session, question, topK).selected();
    }

    public RagDebugRetrieveResponse debugRetrieve(ChatSession session, String question, int topK) {
        RetrievalTrace trace = retrieveTrace(session, question, topK);
        Map<String, Long> selectedTypeDistribution = trace.selected().stream()
                .collect(java.util.stream.Collectors.groupingBy(localRerankingService::chunkType, LinkedHashMap::new, java.util.stream.Collectors.counting()));
        return RagDebugRetrieveResponse.builder()
                .query(question)
                .retrievalMode(trace.retrievalMode().name())
                .scopedNodeId(trace.scopedNodeId())
                .intentType(trace.intent().type().name())
                .sectionNumber(trace.intent().sectionNumber())
                .candidateCount(trace.candidates().size())
                .policyCandidateCount(trace.policyCandidates().size())
                .selectedCount(trace.selected().size())
                .selectedChunkTypes(selectedTypeDistribution)
                .coarseHitCount(trace.coarseTrace().coarseHits().size())
                .coarseHits(trace.coarseTrace().coarseHits().stream()
                        .map(this::toDebugCoarseHit)
                        .toList())
                .fineCandidateCount(trace.coarseTrace().fineCandidateCount())
                .flatGuardrailCandidateCount(trace.coarseTrace().flatGuardrailCandidateCount())
                .fallbackReason(trace.coarseTrace().fallbackReason())
                .parentGroups(trace.parentGroups().stream()
                        .map(this::toDebugParentGroup)
                        .toList())
                .candidateChunks(trace.scored().stream()
                        .sorted(Comparator.comparingDouble(LocalRerankingService.ScoredChunk::score).reversed())
                        .map(this::toDebugChunk)
                        .toList())
                .selectedChunks(trace.selected().stream()
                        .map(chunk -> toDebugChunk(new LocalRerankingService.ScoredChunk(chunk, scoreOf(trace.scored(), chunk))))
                        .toList())
                .promptContextPreview(promptContextPreview(trace.selected()))
                .build();
    }

    private RetrievalTrace retrieveTrace(ChatSession session, String question, int topK) {
        int safeTopK = Math.min(Math.max(1, topK), ragProperties.getMaxTopK());
        int candidateTopK = Math.max(safeTopK, ragProperties.getCandidateTopK());
        LocalRerankingService.RetrievalIntent intent = localRerankingService.detectIntent(question);

        DocumentNode scopedNode = tryResolveScopedNode(session, question);
        String queryVectorLiteral = queryVectorLiteral(question);
        if (scopedNode != null) {
            return doScopedVector(session, question, intent, safeTopK, candidateTopK, scopedNode, queryVectorLiteral);
        }
        if (ragProperties.getRetrieval().getCoarseToFine().isEnabled()) {
            return doCoarseToFineVector(session, question, intent, safeTopK, candidateTopK, queryVectorLiteral);
        }
        return doFlatVector(session, question, intent, safeTopK, candidateTopK, queryVectorLiteral, RetrievalMode.FLAT_VECTOR, null);
    }

    private DocumentNode tryResolveScopedNode(ChatSession session, String question) {
        RagProperties.Retrieval.ScopedVector cfg = ragProperties.getRetrieval().getScopedVector();
        if (!cfg.isEnabled()) {
            return null;
        }
        if (!ragScopeResolverService.hasExplicitScopeHint(question)) {
            return null;
        }
        ScopeResolution resolution = ragScopeResolverService.resolveDeterministicOnly(session, question);
        if (resolution.status() != ScopeResolution.Status.RESOLVED) {
            return null;
        }
        if (resolution.confidence() < cfg.getMinConfidence()) {
            return null;
        }
        if (resolution.node() == null) {
            return null;
        }
        log.info("RAG scoped retrieval: nodeId={} nodeKey={} confidence={}",
                resolution.node().getId(), resolution.node().getNodeKey(), resolution.confidence());
        return resolution.node();
    }

    private RetrievalTrace doScopedVector(ChatSession session,
                                          String question,
                                          LocalRerankingService.RetrievalIntent intent,
                                          int safeTopK,
                                          int candidateTopK,
                                          DocumentNode scopedNode,
                                          String queryVectorLiteral) {
        List<DocumentChunk> candidates = documentChunkRepository.searchByNodeSubtreeVector(
                session.getSubject().getId(),
                scopedNode.getId(),
                queryVectorLiteral,
                ragProperties.getMinChunkChars(),
                candidateTopK
        );

        if (candidates.isEmpty()) {
            log.debug("Scoped retrieval empty for nodeId={} - fallback flat", scopedNode.getId());
            return doFlatVector(session, question, intent, safeTopK, candidateTopK,
                    queryVectorLiteral, RetrievalMode.SCOPED_EMPTY_FALLBACK, scopedNode.getId());
        }

        LocalRerankingService.RerankResult rerankResult =
                localRerankingService.rerank(question, candidates, intent, safeTopK);
        List<DocumentChunk> selected = rerankResult.selected();

        RetrievalTrace trace = new RetrievalTrace(
                intent,
                candidates,
                rerankResult.scored(),
                rerankResult.policyCandidates(),
                rerankResult.parentGroups(),
                selected,
                RetrievalMode.SCOPED_VECTOR,
                scopedNode.getId(),
                CoarseTrace.empty()
        );
        logRetrievalDiagnostics(question, trace);
        return trace;
    }

    private RetrievalTrace doCoarseToFineVector(ChatSession session,
                                                String question,
                                                LocalRerankingService.RetrievalIntent intent,
                                                int safeTopK,
                                                int candidateTopK,
                                                String queryVectorLiteral) {
        Long subjectId = session.getSubject().getId();
        List<DocumentNodeArtifactRepository.CoarseNodeHit> coarseHits;
        try {
            coarseHits = safeList(coarseNodeSearchService.search(subjectId, queryVectorLiteral));
        } catch (RuntimeException ex) {
            log.warn("Coarse retrieval search failed - fallback flat: subjectId={}", subjectId, ex);
            return doFlatVector(session, question, intent, safeTopK, candidateTopK, queryVectorLiteral,
                    RetrievalMode.COARSE_TO_FINE_EMPTY_FALLBACK, null,
                    new CoarseTrace(List.of(), 0, 0, "COARSE_SEARCH_ERROR"));
        }

        if (coarseHits.isEmpty()) {
            return doFlatVector(session, question, intent, safeTopK, candidateTopK, queryVectorLiteral,
                    RetrievalMode.COARSE_TO_FINE_EMPTY_FALLBACK, null,
                    new CoarseTrace(coarseHits, 0, 0, "NO_COARSE_HITS"));
        }

        RagProperties.Retrieval.CoarseToFine config = ragProperties.getRetrieval().getCoarseToFine();
        int perNodeTopK = Math.max(1, config.getFineCandidateTopKPerNode());
        int maxFineCandidates = Math.max(1, config.getMaxFineCandidates());
        List<DocumentChunk> fineCandidates = new ArrayList<>();
        for (DocumentNodeArtifactRepository.CoarseNodeHit hit : coarseHits) {
            if (fineCandidates.size() >= maxFineCandidates) {
                break;
            }
            Long nodeId = hit.getNodeId();
            if (nodeId == null) {
                continue;
            }
            int remaining = maxFineCandidates - fineCandidates.size();
            int nodeLimit = Math.min(perNodeTopK, remaining);
            fineCandidates.addAll(safeList(documentChunkRepository.searchByNodeSubtreeVector(
                    subjectId,
                    nodeId,
                    queryVectorLiteral,
                    ragProperties.getMinChunkChars(),
                    nodeLimit
            )));
        }

        if (fineCandidates.isEmpty()) {
            return doFlatVector(session, question, intent, safeTopK, candidateTopK, queryVectorLiteral,
                    RetrievalMode.COARSE_TO_FINE_INSUFFICIENT_FALLBACK, null,
                    new CoarseTrace(coarseHits, 0, 0, "NO_FINE_CANDIDATES"));
        }

        int guardrailTopK = Math.max(1, config.getFlatGuardrailCandidateTopK());
        List<DocumentChunk> flatGuardrailCandidates = documentChunkRepository.searchBySubjectVector(
                subjectId,
                queryVectorLiteral,
                ragProperties.getMinChunkChars(),
                guardrailTopK,
                intent.sectionNumber()
        );
        List<DocumentChunk> mergedCandidates = dedupeChunks(fineCandidates, flatGuardrailCandidates);

        LocalRerankingService.RerankResult rerankResult =
                localRerankingService.rerank(question, mergedCandidates, intent, safeTopK);
        List<DocumentChunk> selected = rerankResult.selected();
        CoarseTrace coarseTrace = new CoarseTrace(
                coarseHits,
                fineCandidates.size(),
                flatGuardrailCandidates == null ? 0 : flatGuardrailCandidates.size(),
                null
        );
        if (selected.isEmpty()) {
            return doFlatVector(session, question, intent, safeTopK, candidateTopK, queryVectorLiteral,
                    RetrievalMode.COARSE_TO_FINE_ZERO_SELECTED_FALLBACK, null,
                    new CoarseTrace(
                            coarseHits,
                            fineCandidates.size(),
                            flatGuardrailCandidates == null ? 0 : flatGuardrailCandidates.size(),
                            "ZERO_SELECTED_CHUNKS"
                    ));
        }

        RetrievalTrace trace = new RetrievalTrace(
                intent,
                mergedCandidates,
                rerankResult.scored(),
                rerankResult.policyCandidates(),
                rerankResult.parentGroups(),
                selected,
                RetrievalMode.COARSE_TO_FINE_VECTOR,
                null,
                coarseTrace
        );
        logRetrievalDiagnostics(question, trace);
        return trace;
    }

    private RetrievalTrace doFlatVector(ChatSession session,
                                        String question,
                                        LocalRerankingService.RetrievalIntent intent,
                                        int safeTopK,
                                        int candidateTopK,
                                        String queryVectorLiteral,
                                        RetrievalMode retrievalMode,
                                        Long scopedNodeId) {
        return doFlatVector(session, question, intent, safeTopK, candidateTopK, queryVectorLiteral,
                retrievalMode, scopedNodeId, CoarseTrace.empty());
    }

    private RetrievalTrace doFlatVector(ChatSession session,
                                        String question,
                                        LocalRerankingService.RetrievalIntent intent,
                                        int safeTopK,
                                        int candidateTopK,
                                        String queryVectorLiteral,
                                        RetrievalMode retrievalMode,
                                        Long scopedNodeId,
                                        CoarseTrace coarseTrace) {
        List<DocumentChunk> candidates = documentChunkRepository.searchBySubjectVector(
                session.getSubject().getId(),
                queryVectorLiteral,
                ragProperties.getMinChunkChars(),
                candidateTopK,
                intent.sectionNumber()
        );

        LocalRerankingService.RerankResult rerankResult =
                localRerankingService.rerank(question, candidates, intent, safeTopK);
        List<DocumentChunk> selected = rerankResult.selected();

        RetrievalTrace trace = new RetrievalTrace(
                intent,
                candidates,
                rerankResult.scored(),
                rerankResult.policyCandidates(),
                rerankResult.parentGroups(),
                selected,
                retrievalMode,
                scopedNodeId,
                coarseTrace
        );
        logRetrievalDiagnostics(question, trace);
        return trace;
    }

    private String queryVectorLiteral(String question) {
        String embedInput = queryEmbeddingInput(question);
        log.debug("Query embedding input length: {} (raw question length: {})",
                embedInput.length(), question.length());
        List<Double> questionEmbedding = embeddingGateway.embed(embedInput);
        validateEmbeddingDimensions(questionEmbedding);
        return toVectorLiteral(questionEmbedding);
    }

    private String queryEmbeddingInput(String question) {
        String prefix = ragProperties.getAi().getQueryInstructionPrefix();
        return (prefix == null ? "" : prefix) + question;
    }

    private void validateEmbeddingDimensions(List<Double> embedding) {
        int actual = embedding == null ? 0 : embedding.size();
        int expected = ragProperties.getEmbeddingDimensions();
        if (actual != expected) {
            throw new InvalidDataException("Question embedding dimension mismatch: expected %d, got %d"
                    .formatted(expected, actual));
        }
    }

    private void logRetrievalDiagnostics(String question, RetrievalTrace trace) {
        if (!log.isDebugEnabled()) {
            return;
        }
        LocalRerankingService.RetrievalIntent intent = trace.intent();
        List<DocumentChunk> candidates = trace.candidates();
        List<DocumentChunk> selected = trace.selected();
        long sectionMatches = selected.stream()
                .filter(c -> localRerankingService.containsSectionNumber(c.getContent(), intent.sectionNumber())
                        || localRerankingService.containsSectionNumber(c.getSectionPath(), intent.sectionNumber()))
                .count();
        long selectedParents = selected.stream()
                .map(localRerankingService::parentGroupKey)
                .distinct()
                .count();
        Map<String, Long> selectedTypeDistribution = selected.stream()
                .collect(java.util.stream.Collectors.groupingBy(localRerankingService::chunkType, LinkedHashMap::new, java.util.stream.Collectors.counting()));
        List<Long> coarseHitNodes = trace.coarseTrace().coarseHits().stream()
                .map(DocumentNodeArtifactRepository.CoarseNodeHit::getNodeId)
                .toList();
        log.debug("RAG retrieval diagnostics: mode={}, question='{}', intentType={}, sectionIntent={}, candidates={}, policyCandidates={}, selected={}, parentGroups={}, sectionMatches={}, selectedTypes={}, coarseHitNodes={}, fineCandidates={}, flatGuardrailCandidates={}, fallbackReason={}",
                trace.retrievalMode(),
                question,
                intent.type(),
                intent.sectionNumber(),
                candidates.size(),
                trace.policyCandidates().size(),
                selected.size(),
                selectedParents,
                sectionMatches,
                selectedTypeDistribution,
                coarseHitNodes,
                trace.coarseTrace().fineCandidateCount(),
                trace.coarseTrace().flatGuardrailCandidateCount(),
                trace.coarseTrace().fallbackReason());
    }

    @SafeVarargs
    private List<DocumentChunk> dedupeChunks(List<DocumentChunk>... sources) {
        Map<String, DocumentChunk> byKey = new LinkedHashMap<>();
        for (List<DocumentChunk> source : sources) {
            if (source == null) {
                continue;
            }
            for (DocumentChunk chunk : source) {
                if (chunk == null) {
                    continue;
                }
                byKey.putIfAbsent(chunkKey(chunk), chunk);
            }
        }
        return List.copyOf(byKey.values());
    }

    private String chunkKey(DocumentChunk chunk) {
        return chunk.getId() == null
                ? "identity:" + System.identityHashCode(chunk)
                : "id:" + chunk.getId();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private RagDebugRetrieveResponse.CoarseHit toDebugCoarseHit(DocumentNodeArtifactRepository.CoarseNodeHit hit) {
        return RagDebugRetrieveResponse.CoarseHit.builder()
                .artifactId(hit.getArtifactId())
                .nodeId(hit.getNodeId())
                .documentId(hit.getDocumentId())
                .documentTitle(hit.getDocumentTitle())
                .nodeType(hit.getNodeType())
                .sectionPath(hit.getSectionPath())
                .distance(hit.getDistance())
                .build();
    }

    private RagDebugRetrieveResponse.ParentGroup toDebugParentGroup(LocalRerankingService.ScoredParentGroup group) {
        return RagDebugRetrieveResponse.ParentGroup.builder()
                .parentKey(group.parentKey())
                .score(group.score())
                .childCount(group.children().size())
                .children(group.children().stream()
                        .sorted(Comparator.comparingDouble(LocalRerankingService.ScoredChunk::score).reversed())
                        .map(this::toDebugChunk)
                        .toList())
                .build();
    }

    private RagDebugRetrieveResponse.Chunk toDebugChunk(LocalRerankingService.ScoredChunk scoredChunk) {
        DocumentChunk chunk = scoredChunk.chunk();
        DocumentNode node = chunk.getNode();
        DocumentNode parentNode = chunk.getParentNode();
        return RagDebugRetrieveResponse.Chunk.builder()
                .chunkId(chunk.getId())
                .chunkIndex(chunk.getChunkIndex())
                .sourceOrder(chunk.getSourceOrder())
                .chunkType(localRerankingService.chunkType(chunk))
                .documentId(chunk.getDocument() == null ? null : chunk.getDocument().getId())
                .documentTitle(chunk.getDocument() == null ? null : chunk.getDocument().getTitle())
                .nodeId(node == null ? null : node.getId())
                .nodeKey(node == null ? null : node.getNodeKey())
                .parentNodeId(parentNode == null ? null : parentNode.getId())
                .parentNodeKey(parentNode == null ? null : parentNode.getNodeKey())
                .sectionPath(chunk.getSectionPath())
                .pageFrom(chunk.getPageFrom())
                .pageTo(chunk.getPageTo())
                .score(scoredChunk.score())
                .snippet(snippet(chunk.getContent()))
                .build();
    }

    private double scoreOf(List<LocalRerankingService.ScoredChunk> scored, DocumentChunk chunk) {
        return scored.stream()
                .filter(scoredChunk -> sameChunk(scoredChunk.chunk(), chunk))
                .findFirst()
                .map(LocalRerankingService.ScoredChunk::score)
                .orElse(0.0);
    }

    private boolean sameChunk(DocumentChunk left, DocumentChunk right) {
        if (left == right) {
            return true;
        }
        return left != null && right != null && left.getId() != null && left.getId().equals(right.getId());
    }

    private String promptContextPreview(List<DocumentChunk> selected) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < selected.size(); i++) {
            DocumentChunk chunk = selected.get(i);
            builder.append("[Source ").append(i + 1).append("]\n")
                    .append("Document: ").append(chunk.getDocument() == null ? "N/A" : chunk.getDocument().getTitle()).append("\n")
                    .append("Path: ").append(chunk.getSectionPath() == null ? "N/A" : chunk.getSectionPath()).append("\n")
                    .append("Pages: ").append(pageRange(chunk)).append("\n")
                    .append("Chunk type: ").append(localRerankingService.chunkType(chunk)).append("\n")
                    .append("Content:\n").append(snippet(chunk.getContent())).append("\n\n");
        }
        return builder.toString().trim();
    }

    private String pageRange(DocumentChunk chunk) {
        Integer pageFrom = chunk.getPageFrom();
        Integer pageTo = chunk.getPageTo();
        if (pageFrom == null && pageTo == null) {
            return "N/A";
        }
        if (pageFrom != null && pageTo != null && !pageFrom.equals(pageTo)) {
            return pageFrom + "-" + pageTo;
        }
        return String.valueOf(pageFrom != null ? pageFrom : pageTo);
    }

    private String snippet(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 320) {
            return normalized;
        }
        return normalized.substring(0, 317) + "...";
    }

    private String toVectorLiteral(List<Double> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) builder.append(',');
            builder.append(values.get(i));
        }
        builder.append(']');
        return builder.toString();
    }

    private enum RetrievalMode {
        FLAT_VECTOR,
        SCOPED_VECTOR,
        SCOPED_EMPTY_FALLBACK,
        COARSE_TO_FINE_VECTOR,
        COARSE_TO_FINE_EMPTY_FALLBACK,
        COARSE_TO_FINE_INSUFFICIENT_FALLBACK,
        COARSE_TO_FINE_ZERO_SELECTED_FALLBACK
    }

    private record RetrievalTrace(
            LocalRerankingService.RetrievalIntent intent,
            List<DocumentChunk> candidates,
            List<LocalRerankingService.ScoredChunk> scored,
            List<LocalRerankingService.ScoredChunk> policyCandidates,
            List<LocalRerankingService.ScoredParentGroup> parentGroups,
            List<DocumentChunk> selected,
            RetrievalMode retrievalMode,
            Long scopedNodeId,
            CoarseTrace coarseTrace
    ) {
        private RetrievalTrace {
            coarseTrace = coarseTrace == null ? CoarseTrace.empty() : coarseTrace;
        }
    }

    private record CoarseTrace(
            List<DocumentNodeArtifactRepository.CoarseNodeHit> coarseHits,
            int fineCandidateCount,
            int flatGuardrailCandidateCount,
            String fallbackReason
    ) {
        private CoarseTrace {
            coarseHits = coarseHits == null ? List.of() : List.copyOf(coarseHits);
        }

        static CoarseTrace empty() {
            return new CoarseTrace(List.of(), 0, 0, null);
        }
    }
}
