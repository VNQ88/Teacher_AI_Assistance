package com.example.teacherassistantai.service;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.dto.response.RagDebugRetrieveResponse;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.integration.ai.AiEmbeddingGateway;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    public List<DocumentChunk> retrieve(ChatSession session, String question, int topK) {
        return retrieveTrace(session, question, topK).selected();
    }

    public RagDebugRetrieveResponse debugRetrieve(ChatSession session, String question, int topK) {
        RetrievalTrace trace = retrieveTrace(session, question, topK);
        Map<String, Long> selectedTypeDistribution = trace.selected().stream()
                .collect(java.util.stream.Collectors.groupingBy(localRerankingService::chunkType, LinkedHashMap::new, java.util.stream.Collectors.counting()));
        return RagDebugRetrieveResponse.builder()
                .query(question)
                .intentType(trace.intent().type().name())
                .sectionNumber(trace.intent().sectionNumber())
                .candidateCount(trace.candidates().size())
                .policyCandidateCount(trace.policyCandidates().size())
                .selectedCount(trace.selected().size())
                .selectedChunkTypes(selectedTypeDistribution)
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

        List<Double> questionEmbedding = embeddingGateway.embed(question);
        validateEmbeddingDimensions(questionEmbedding);

        String literal = toVectorLiteral(questionEmbedding);
        List<DocumentChunk> candidates = documentChunkRepository.searchBySubjectVector(
                session.getSubject().getId(),
                literal,
                ragProperties.getMinChunkChars(),
                candidateTopK,
                intent.sectionNumber()
        );

        LocalRerankingService.RerankResult rerankResult = localRerankingService.rerank(question, candidates, intent, safeTopK);
        List<DocumentChunk> selected = rerankResult.selected();

        logRetrievalDiagnostics(question, intent, candidates, rerankResult.policyCandidates(), selected);
        return new RetrievalTrace(
                intent,
                candidates,
                rerankResult.scored(),
                rerankResult.policyCandidates(),
                rerankResult.parentGroups(),
                selected
        );
    }

    private void validateEmbeddingDimensions(List<Double> embedding) {
        int actual = embedding == null ? 0 : embedding.size();
        int expected = ragProperties.getEmbeddingDimensions();
        if (actual != expected) {
            throw new InvalidDataException("Question embedding dimension mismatch: expected %d, got %d"
                    .formatted(expected, actual));
        }
    }

    private void logRetrievalDiagnostics(String question,
                                         LocalRerankingService.RetrievalIntent intent,
                                         List<DocumentChunk> candidates,
                                         List<LocalRerankingService.ScoredChunk> policyCandidates,
                                         List<DocumentChunk> selected) {
        if (!log.isDebugEnabled()) {
            return;
        }
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
        log.debug("RAG retrieval diagnostics: question='{}', intentType={}, sectionIntent={}, candidates={}, policyCandidates={}, selected={}, parentGroups={}, sectionMatches={}, selectedTypes={}",
                question,
                intent.type(),
                intent.sectionNumber(),
                candidates.size(),
                policyCandidates.size(),
                selected.size(),
                selectedParents,
                sectionMatches,
                selectedTypeDistribution);
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

    private record RetrievalTrace(
            LocalRerankingService.RetrievalIntent intent,
            List<DocumentChunk> candidates,
            List<LocalRerankingService.ScoredChunk> scored,
            List<LocalRerankingService.ScoredChunk> policyCandidates,
            List<LocalRerankingService.ScoredParentGroup> parentGroups,
            List<DocumentChunk> selected
    ) {
    }
}
