package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
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
public class LocalRerankingService {

    private static final Pattern SECTION_INTENT_PATTERN = Pattern.compile(
            "(?iu)(?:\\b(section|chapter|part)\\s*(\\d{1,3})\\b|\\b(phần|phan|chương|chuong|mục|muc)\\s*(\\d{1,3})\\b)");

    public RetrievalIntent detectIntent(String question) {
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

    public RerankResult rerank(String question, List<DocumentChunk> candidates, RetrievalIntent intent, int safeTopK) {
        List<ScoredChunk> scored = scoreCandidates(question, candidates, intent);
        List<ScoredChunk> policyCandidates = applyChunkTypePolicy(scored, intent);
        ParentSelection parentSelection = selectParentAware(policyCandidates, safeTopK);
        return new RerankResult(scored, policyCandidates, parentSelection.parentGroups(), parentSelection.selected());
    }

    public boolean containsSectionNumber(String content, Integer sectionNumber) {
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

    public String parentGroupKey(DocumentChunk chunk) {
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

    public String chunkType(DocumentChunk chunk) {
        if (chunk.getChunkType() == null || chunk.getChunkType().isBlank()) {
            return "TEXT";
        }
        return chunk.getChunkType().toUpperCase(Locale.ROOT);
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
        if (!allowed.isEmpty()) {
            return allowed;
        }
        List<ScoredChunk> nonCitation = scored.stream()
                .filter(scoredChunk -> !chunkType(scoredChunk.chunk()).equals("CITATION"))
                .toList();
        return nonCitation.isEmpty() ? scored : nonCitation;
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

    private boolean isAllowedChunkType(DocumentChunk chunk, RetrievalIntent intent) {
        String chunkType = chunkType(chunk);
        return switch (intent.type()) {
            case REVIEW_QUESTIONS -> chunkType.equals("REVIEW_QUESTIONS") || chunkType.equals("TEXT") || chunkType.equals("SUMMARY");
            case SUMMARY -> chunkType.equals("SUMMARY") || chunkType.equals("TEXT") || chunkType.equals("REVIEW_QUESTIONS");
            case FACTUAL -> chunkType.equals("TEXT") || chunkType.equals("SUMMARY");
        };
    }

    private double chunkTypeBoost(DocumentChunk chunk, RetrievalIntent intent) {
        String chunkType = chunkType(chunk);
        return switch (intent.type()) {
            case REVIEW_QUESTIONS -> switch (chunkType) {
                case "REVIEW_QUESTIONS" -> 0.35;
                case "SUMMARY" -> 0.02;
                case "TEXT" -> 0.05;
                case "CITATION" -> -1.00;
                default -> -0.20;
            };
            case SUMMARY -> switch (chunkType) {
                case "SUMMARY" -> 0.30;
                case "TEXT" -> 0.05;
                case "REVIEW_QUESTIONS" -> -0.10;
                case "CITATION" -> -1.00;
                default -> -0.30;
            };
            case FACTUAL -> switch (chunkType) {
                case "TEXT" -> 0.12;
                case "SUMMARY" -> -0.05;
                case "CITATION" -> -1.00;
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

    private int sourceOrder(DocumentChunk chunk) {
        if (chunk.getSourceOrder() != null) {
            return chunk.getSourceOrder();
        }
        if (chunk.getChunkIndex() != null) {
            return chunk.getChunkIndex();
        }
        return Integer.MAX_VALUE;
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    public enum RetrievalIntentType {
        FACTUAL,
        SUMMARY,
        REVIEW_QUESTIONS
    }

    public record RetrievalIntent(RetrievalIntentType type, Integer sectionNumber) {
    }

    public record ScoredChunk(DocumentChunk chunk, double score) {
    }

    public record ScoredParentGroup(String parentKey, List<ScoredChunk> children, double score) {
    }

    private record ParentSelection(List<DocumentChunk> selected, List<ScoredParentGroup> parentGroups) {
    }

    public record RerankResult(
            List<ScoredChunk> scored,
            List<ScoredChunk> policyCandidates,
            List<ScoredParentGroup> parentGroups,
            List<DocumentChunk> selected
    ) {
    }
}
