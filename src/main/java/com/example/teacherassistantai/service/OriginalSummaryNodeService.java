package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OriginalSummaryNodeService {

    private static final String SUMMARY_NODE_TYPE = "summary";

    private final DocumentNodeRepository documentNodeRepository;
    private final DocumentChunkRepository documentChunkRepository;

    public Optional<OriginalSummary> findForChapter(DocumentNode chapterNode) {
        if (chapterNode == null || !"chapter".equals(chapterNode.getNodeType()) || chapterNode.getId() == null) {
            return Optional.empty();
        }

        List<DocumentNode> directSummaries = documentNodeRepository
                .findByParentIdAndNodeTypeOrderByOrderIndexAsc(chapterNode.getId(), SUMMARY_NODE_TYPE);
        Optional<OriginalSummary> direct = directSummaries.stream()
                .findFirst()
                .flatMap(this::toOriginalSummary);
        if (direct.isPresent()) {
            return direct;
        }

        if (chapterNode.getDocument() == null || chapterNode.getDocument().getId() == null) {
            return Optional.empty();
        }
        List<DocumentNode> documentSummaries = documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(
                chapterNode.getDocument().getId(), SUMMARY_NODE_TYPE);
        return documentSummaries.stream()
                .filter(summaryNode -> matchesChapter(summaryNode, chapterNode))
                .min(Comparator.comparingInt(summaryNode -> orderDistance(summaryNode, chapterNode)))
                .flatMap(this::toOriginalSummary);
    }

    private Optional<OriginalSummary> toOriginalSummary(DocumentNode summaryNode) {
        List<DocumentChunk> chunks = summaryNode.getDocument() == null || summaryNode.getDocument().getId() == null
                ? List.of()
                : documentChunkRepository.findByDocumentIdAndNodeIdOrderBySourceOrderAsc(
                        summaryNode.getDocument().getId(), summaryNode.getId());
        String content = summaryNode.getContent();
        if (content == null || content.isBlank()) {
            content = chunks.stream()
                    .map(DocumentChunk::getContent)
                    .filter(value -> value != null && !value.isBlank())
                    .reduce((left, right) -> left + "\n\n" + right)
                    .orElse("");
        }
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new OriginalSummary(summaryNode, content.trim(), chunks));
    }

    private boolean matchesChapter(DocumentNode summaryNode, DocumentNode chapterNode) {
        String summaryText = normalize(value(summaryNode.getTitle()) + " " + value(summaryNode.getSectionPath()));
        String chapterTitle = normalize(value(chapterNode.getTitle()));
        String chapterPath = normalize(value(chapterNode.getSectionPath()));
        ChapterNumber chapterNumber = extractChapterNumber(chapterTitle + " " + chapterPath);

        if (chapterNumber != null) {
            for (String value : chapterNumber.variants()) {
                if (containsPhrase(summaryText, "chuong " + value) || summaryText.contains(" " + value + ".")) {
                    return true;
                }
            }
        }
        String searchableTitle = removeLeadingChapterMarker(chapterTitle);
        return !searchableTitle.isBlank() && containsPhrase(summaryText, searchableTitle);
    }

    private ChapterNumber extractChapterNumber(String normalizedText) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\bchuong\\s+([ivxlcdm]+|\\d+)")
                .matcher(normalizedText);
        if (!matcher.find()) {
            return null;
        }
        String raw = matcher.group(1);
        if (raw.matches("[ivxlcdm]+")) {
            int value = romanToInt(raw);
            return new ChapterNumber(raw, value > 0 ? String.valueOf(value) : raw);
        }
        return new ChapterNumber(raw, raw);
    }

    private String removeLeadingChapterMarker(String normalizedTitle) {
        return normalizedTitle
                .replaceFirst("^\\s*chuong\\s+([ivxlcdm]+|\\d+)\\s*[:.\\-]*\\s*", "")
                .trim();
    }

    private int orderDistance(DocumentNode summaryNode, DocumentNode chapterNode) {
        int summaryOrder = summaryNode.getOrderIndex() == null ? Integer.MAX_VALUE : summaryNode.getOrderIndex();
        int chapterOrder = chapterNode.getOrderIndex() == null ? Integer.MAX_VALUE : chapterNode.getOrderIndex();
        return Math.abs(summaryOrder - chapterOrder);
    }

    private boolean containsPhrase(String text, String phrase) {
        return text != null && phrase != null && !phrase.isBlank() && text.contains(" " + phrase.trim() + " ");
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd')
                .replace('Đ', 'D')
                .toLowerCase(Locale.ROOT);
        return " " + normalized.replaceAll("[^\\p{L}\\p{N}\\s.]", " ")
                .replaceAll("\\s+", " ")
                .trim() + " ";
    }

    private int romanToInt(String value) {
        int total = 0;
        int previous = 0;
        for (int i = value.length() - 1; i >= 0; i--) {
            int current = switch (value.charAt(i)) {
                case 'i' -> 1;
                case 'v' -> 5;
                case 'x' -> 10;
                case 'l' -> 50;
                case 'c' -> 100;
                case 'd' -> 500;
                case 'm' -> 1000;
                default -> 0;
            };
            total += current < previous ? -current : current;
            previous = current;
        }
        return total;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    public record OriginalSummary(DocumentNode summaryNode, String content, List<DocumentChunk> sources) {
    }

    private record ChapterNumber(String raw, String normalized) {
        private List<String> variants() {
            java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
            values.add(raw);
            values.add(normalized);
            if (normalized != null && normalized.matches("\\d+")) {
                int number = Integer.parseInt(normalized);
                if (number > 0 && number <= 3999) {
                    values.add(intToRoman(number));
                }
            }
            return values.stream().filter(value -> value != null && !value.isBlank()).toList();
        }
    }

    private static String intToRoman(int value) {
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] symbols = {"m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i"};
        StringBuilder result = new StringBuilder();
        int remaining = value;
        for (int i = 0; i < values.length; i++) {
            while (remaining >= values[i]) {
                result.append(symbols[i]);
                remaining -= values[i];
            }
        }
        return result.toString();
    }
}
