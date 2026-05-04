package com.example.teacherassistantai.service;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.dto.response.RagDebugRetrieveResponse;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.integration.gemini.GeminiEmbeddingGateway;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class VectorRetrievalService {

    private static final Pattern SECTION_INTENT_PATTERN = Pattern.compile(
            "(?iu)(?:\\b(section|chapter|part)\\s*(\\d{1,3})\\b|\\b(phần|phan|chương|chuong|mục|muc)\\s*(\\d{1,3})\\b)");

    private final GeminiEmbeddingGateway embeddingGateway;
    private final DocumentChunkRepository documentChunkRepository;
    private final RagProperties ragProperties;

    public List<DocumentChunk> retrieve(ChatSession session, String question, int topK) {
        return retrieveTrace(session, question, topK).selected();
    }

    public RagDebugRetrieveResponse debugRetrieve(ChatSession session, String question, int topK) {
        RetrievalTrace trace = retrieveTrace(session, question, topK);
        Map<String, Long> selectedTypeDistribution = trace.selected().stream()
                .collect(java.util.stream.Collectors.groupingBy(this::chunkType, LinkedHashMap::new, java.util.stream.Collectors.counting()));
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
                        .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                        .map(this::toDebugChunk)
                        .toList())
                .selectedChunks(trace.selected().stream()
                        .map(chunk -> toDebugChunk(new ScoredChunk(chunk, scoreOf(trace.scored(), chunk))))
                        .toList())
                .promptContextPreview(promptContextPreview(trace.selected()))
                .build();
    }

    private RetrievalTrace retrieveTrace(ChatSession session, String question, int topK) {
        int safeTopK = Math.min(Math.max(1, topK), ragProperties.getMaxTopK());
        int candidateTopK = Math.max(safeTopK, ragProperties.getCandidateTopK());
        RetrievalIntent intent = detectIntent(question);

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

        List<ScoredChunk> scored = scoreCandidates(question, candidates, intent);
        List<ScoredChunk> policyCandidates = applyChunkTypePolicy(scored, intent);
        ParentSelection parentSelection = selectParentAware(policyCandidates, safeTopK);
        List<DocumentChunk> selected = parentSelection.selected();

        logRetrievalDiagnostics(question, intent, candidates, policyCandidates, selected);
        return new RetrievalTrace(intent, candidates, scored, policyCandidates, parentSelection.parentGroups(), selected);
    }

    private List<ScoredChunk> scoreCandidates(String question, List<DocumentChunk> candidates, RetrievalIntent intent) {
        List<ScoredChunk> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            DocumentChunk chunk = candidates.get(i);
            scored.add(new ScoredChunk(chunk, rerankScore(question, chunk, intent, i, candidates.size())));
        }
        return scored;
    }

    private List<ScoredChunk> applyChunkTypePolicy(List<ScoredChunk> scored, RetrievalIntent intent) {
        List<ScoredChunk> allowed = scored.stream()
                .filter(scoredChunk -> isAllowedChunkType(scoredChunk.chunk(), intent))
                .toList();
        return allowed.isEmpty() ? scored : allowed;
    }

    private ParentSelection selectParentAware(List<ScoredChunk> scored, int safeTopK) {
        if (scored.isEmpty()) {
            return new ParentSelection(List.of(), List.of());
        }

        Map<String, List<ScoredChunk>> byParent = new LinkedHashMap<>();
        for (ScoredChunk scoredChunk : scored) {
            byParent.computeIfAbsent(parentGroupKey(scoredChunk.chunk()), ignored -> new ArrayList<>())
                    .add(scoredChunk);
        }

        int parentTopK = Math.max(1, Math.min(safeTopK, (int) Math.ceil(safeTopK / 2.0)));
        int childPerParent = Math.max(1, Math.min(4, (int) Math.ceil((double) safeTopK / parentTopK)));

        List<ScoredParentGroup> parentGroups = byParent.entrySet().stream()
                .map(entry -> new ScoredParentGroup(entry.getKey(), entry.getValue(), parentScore(entry.getValue())))
                .sorted(Comparator.comparingDouble(ScoredParentGroup::score).reversed())
                .toList();

        List<DocumentChunk> selected = new ArrayList<>();
        Set<Long> selectedIds = new HashSet<>();
        for (ScoredParentGroup group : parentGroups.stream().limit(parentTopK).toList()) {
            List<ScoredChunk> children = group.children().stream()
                    .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                    .limit(childPerParent)
                    .sorted(Comparator.comparingInt(scoredChunk -> sourceOrder(scoredChunk.chunk())))
                    .toList();
            for (ScoredChunk child : children) {
                addSelected(selected, selectedIds, child.chunk(), safeTopK);
            }
        }

        if (selected.size() < safeTopK) {
            scored.stream()
                    .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                    .forEach(scoredChunk -> addSelected(selected, selectedIds, scoredChunk.chunk(), safeTopK));
        }
        return new ParentSelection(selected, parentGroups);
    }

    private void addSelected(List<DocumentChunk> selected, Set<Long> selectedIds, DocumentChunk chunk, int safeTopK) {
        if (selected.size() >= safeTopK) {
            return;
        }
        Long id = chunk.getId();
        if (id != null && !selectedIds.add(id)) {
            return;
        }
        selected.add(chunk);
    }

    private void validateEmbeddingDimensions(List<Double> embedding) {
        int actual = embedding == null ? 0 : embedding.size();
        int expected = ragProperties.getEmbeddingDimensions();
        if (actual != expected) {
            throw new InvalidDataException("Question embedding dimension mismatch: expected %d, got %d"
                    .formatted(expected, actual));
        }
    }

    private double rerankScore(String question,
                               DocumentChunk chunk,
                               RetrievalIntent intent,
                               int candidateRank,
                               int candidateCount) {
        Set<String> questionTokens = normalizeTokens(question);
        Set<String> chunkTokens = normalizeTokens(chunk.getContent() + " " + safeString(chunk.getSectionPath()));
        if (questionTokens.isEmpty() || chunkTokens.isEmpty()) {
            return 0.0;
        }

        int overlap = 0;
        for (String token : questionTokens) {
            if (chunkTokens.contains(token)) {
                overlap++;
            }
        }

        double overlapRatio = (double) overlap / questionTokens.size();
        double lengthPrior = Math.min(1.0, Math.max(0.2, chunk.getContent().length() / 1200.0));

        double vectorRankPrior = candidateCount <= 1
                ? 1.0
                : 1.0 - ((double) candidateRank / (candidateCount - 1));

        double sectionBoost = 0.0;
        if (intent.sectionNumber() != null
                && (containsSectionNumber(chunk.getContent(), intent.sectionNumber())
                || containsSectionNumber(chunk.getSectionPath(), intent.sectionNumber()))) {
            sectionBoost = 1.0;
        }

        return overlapRatio * 0.45
                + vectorRankPrior * 0.25
                + sectionBoost * 0.20
                + lengthPrior * 0.10
                + chunkTypeBoost(chunk, intent);
    }

    private Set<String> normalizeTokens(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }

        String normalized = text.toLowerCase().replaceAll("[^\\p{L}\\p{N}\\s]", " ");
        String[] rawTokens = normalized.trim().split("\\s+");
        Set<String> tokens = new HashSet<>();
        for (String token : rawTokens) {
            if (token.length() >= 2 || token.chars().allMatch(Character::isDigit)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private RetrievalIntent detectIntent(String question) {
        if (question == null || question.isBlank()) {
            return new RetrievalIntent(RetrievalIntentType.FACTUAL, null);
        }
        RetrievalIntentType type = detectIntentType(question);
        Matcher matcher = SECTION_INTENT_PATTERN.matcher(question);
        if (!matcher.find()) {
            return new RetrievalIntent(type, null);
        }

        String number = matcher.group(2) != null ? matcher.group(2) : matcher.group(4);
        try {
            return new RetrievalIntent(type, Integer.parseInt(number));
        } catch (NumberFormatException ex) {
            return new RetrievalIntent(type, null);
        }
    }

    private RetrievalIntentType detectIntentType(String question) {
        String lower = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\b(tóm tắt|tom tat|khái quát|khai quat|tổng quan|tong quan|ý chính|y chinh)\\b.*")) {
            return RetrievalIntentType.SUMMARY;
        }
        if (lower.matches(".*\\b(câu hỏi ôn tập|cau hoi on tap|ôn tập|on tap|luyện tập|luyen tap|đề cương|de cuong|bài tập|bai tap)\\b.*")) {
            return RetrievalIntentType.REVIEW_QUESTIONS;
        }
        return RetrievalIntentType.FACTUAL;
    }

    private boolean containsSectionNumber(String content, Integer sectionNumber) {
        if (content == null || sectionNumber == null) {
            return false;
        }
        String lowered = content.toLowerCase();
        String n = sectionNumber.toString();
        return lowered.contains("phan " + n)
                || lowered.contains("phần " + n)
                || lowered.contains("chuong " + n)
                || lowered.contains("chương " + n)
                || lowered.contains("muc " + n)
                || lowered.contains("mục " + n)
                || lowered.contains("section " + n)
                || lowered.contains("chapter " + n)
                || lowered.contains("part " + n);
    }

    private boolean isAllowedChunkType(DocumentChunk chunk, RetrievalIntent intent) {
        String chunkType = chunkType(chunk);
        return switch (intent.type()) {
            case REVIEW_QUESTIONS -> chunkType.equals("REVIEW_QUESTIONS") || chunkType.equals("TEXT");
            case SUMMARY -> chunkType.equals("SUMMARY") || chunkType.equals("TEXT");
            case FACTUAL -> chunkType.equals("TEXT") || chunkType.equals("SUMMARY");
        };
    }

    private double chunkTypeBoost(DocumentChunk chunk, RetrievalIntent intent) {
        String chunkType = chunkType(chunk);
        return switch (intent.type()) {
            case REVIEW_QUESTIONS -> switch (chunkType) {
                case "REVIEW_QUESTIONS" -> 0.35;
                case "TEXT" -> 0.05;
                default -> -0.20;
            };
            case SUMMARY -> switch (chunkType) {
                case "SUMMARY" -> 0.30;
                case "TEXT" -> 0.05;
                default -> -0.30;
            };
            case FACTUAL -> switch (chunkType) {
                case "TEXT" -> 0.12;
                case "SUMMARY" -> -0.05;
                default -> -0.50;
            };
        };
    }

    private double parentScore(List<ScoredChunk> children) {
        List<ScoredChunk> sorted = children.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .toList();
        double max = sorted.getFirst().score();
        double averageTop = sorted.stream()
                .limit(Math.min(3, sorted.size()))
                .mapToDouble(ScoredChunk::score)
                .average()
                .orElse(max);
        double continuityBoost = Math.min(0.08, Math.max(0, sorted.size() - 1) * 0.02);
        return max * 0.65 + averageTop * 0.30 + continuityBoost;
    }

    private String parentGroupKey(DocumentChunk chunk) {
        DocumentNode parentNode = chunk.getParentNode();
        if (parentNode != null && parentNode.getId() != null) {
            return "p:" + parentNode.getId();
        }
        DocumentNode node = chunk.getNode();
        if (node != null && node.getId() != null) {
            return "n:" + node.getId();
        }
        return "c:" + (chunk.getId() == null ? System.identityHashCode(chunk) : chunk.getId());
    }

    private int sourceOrder(DocumentChunk chunk) {
        if (chunk.getSourceOrder() != null) {
            return chunk.getSourceOrder();
        }
        if (chunk.getChunkIndex() != null) {
            return chunk.getChunkIndex();
        }
        return Integer.MAX_VALUE;
    }

    private String chunkType(DocumentChunk chunk) {
        if (chunk.getChunkType() == null || chunk.getChunkType().isBlank()) {
            return "TEXT";
        }
        return chunk.getChunkType().toUpperCase(Locale.ROOT);
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private void logRetrievalDiagnostics(String question,
                                         RetrievalIntent intent,
                                         List<DocumentChunk> candidates,
                                         List<ScoredChunk> policyCandidates,
                                         List<DocumentChunk> selected) {
        if (!log.isDebugEnabled()) {
            return;
        }
        long sectionMatches = selected.stream()
                .filter(c -> containsSectionNumber(c.getContent(), intent.sectionNumber())
                        || containsSectionNumber(c.getSectionPath(), intent.sectionNumber()))
                .count();
        long selectedParents = selected.stream()
                .map(this::parentGroupKey)
                .distinct()
                .count();
        Map<String, Long> selectedTypeDistribution = selected.stream()
                .collect(java.util.stream.Collectors.groupingBy(this::chunkType, LinkedHashMap::new, java.util.stream.Collectors.counting()));
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

    private RagDebugRetrieveResponse.ParentGroup toDebugParentGroup(ScoredParentGroup group) {
        return RagDebugRetrieveResponse.ParentGroup.builder()
                .parentKey(group.parentKey())
                .score(group.score())
                .childCount(group.children().size())
                .children(group.children().stream()
                        .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                        .map(this::toDebugChunk)
                        .toList())
                .build();
    }

    private RagDebugRetrieveResponse.Chunk toDebugChunk(ScoredChunk scoredChunk) {
        DocumentChunk chunk = scoredChunk.chunk();
        DocumentNode node = chunk.getNode();
        DocumentNode parentNode = chunk.getParentNode();
        return RagDebugRetrieveResponse.Chunk.builder()
                .chunkId(chunk.getId())
                .chunkIndex(chunk.getChunkIndex())
                .sourceOrder(chunk.getSourceOrder())
                .chunkType(chunkType(chunk))
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

    private double scoreOf(List<ScoredChunk> scored, DocumentChunk chunk) {
        return scored.stream()
                .filter(scoredChunk -> sameChunk(scoredChunk.chunk(), chunk))
                .findFirst()
                .map(ScoredChunk::score)
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
                    .append("Chunk type: ").append(chunkType(chunk)).append("\n")
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

    private enum RetrievalIntentType {
        FACTUAL,
        SUMMARY,
        REVIEW_QUESTIONS
    }

    private record RetrievalIntent(RetrievalIntentType type, Integer sectionNumber) {
    }

    private record ScoredChunk(DocumentChunk chunk, double score) {
    }

    private record ScoredParentGroup(String parentKey, List<ScoredChunk> children, double score) {
    }

    private record ParentSelection(List<DocumentChunk> selected, List<ScoredParentGroup> parentGroups) {
    }

    private record RetrievalTrace(
            RetrievalIntent intent,
            List<DocumentChunk> candidates,
            List<ScoredChunk> scored,
            List<ScoredChunk> policyCandidates,
            List<ScoredParentGroup> parentGroups,
            List<DocumentChunk> selected
    ) {
    }
}
