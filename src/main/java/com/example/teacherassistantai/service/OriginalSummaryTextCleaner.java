package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

@Service
public class OriginalSummaryTextCleaner {

    private static final int MIN_OVERLAP_CHARS = 40;
    private static final int MAX_OVERLAP_CHARS = 300;

    public CleanedOriginalSummary clean(DocumentNode summaryNode, List<DocumentChunk> chunks) {
        List<DocumentChunk> safeChunks = chunks == null ? List.of() : chunks;
        StringBuilder combined = new StringBuilder();
        List<DocumentChunk> cleanedChunks = new ArrayList<>();
        CleaningStats.Accumulator stats = new CleaningStats.Accumulator();

        int originalChars = 0;
        for (DocumentChunk chunk : safeChunks) {
            String raw = chunk == null ? null : chunk.getContent();
            if (raw == null || raw.isBlank()) {
                continue;
            }
            originalChars += raw.length();
            CleanedText cleaned = cleanText(summaryNode, raw);
            stats.add(cleaned.stats());
            if (cleaned.text().isBlank()) {
                continue;
            }

            String segment = removeOverlap(combined, cleaned.text(), stats);
            if (segment.isBlank()) {
                continue;
            }
            if (!combined.isEmpty()) {
                combined.append("\n\n");
            }
            combined.append(segment);
            cleanedChunks.add(copyChunkWithContent(chunk, segment));
        }

        String cleanedText = normalizeParagraphSpacing(combined.toString());
        return new CleanedOriginalSummary(
                cleanedText,
                cleanedChunks,
                new CleaningStats(
                        originalChars,
                        cleanedText.length(),
                        stats.removedBreadcrumbCount,
                        stats.removedHeadingCount,
                        stats.removedOverlapCount
                )
        );
    }

    public CleanedText cleanText(DocumentNode summaryNode, String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return new CleanedText("", new CleaningStats(0, 0, 0, 0, 0));
        }
        String normalizedText = rawText.replace("\r\n", "\n").replace('\r', '\n');
        String title = summaryNode == null ? null : summaryNode.getTitle();
        String path = summaryNode == null ? null : summaryNode.getSectionPath();

        int removedBreadcrumb = 0;
        int removedHeading = 0;
        List<String> output = new ArrayList<>();
        for (String rawLine : normalizedText.split("\n", -1)) {
            String line = stripMarkdownHeading(rawLine.trim());
            if (line.isBlank()) {
                output.add("");
                continue;
            }
            if (isBreadcrumbLine(line, title, path)) {
                removedBreadcrumb++;
                continue;
            }
            if (isSummaryHeadingLine(line, title)) {
                removedHeading++;
                continue;
            }
            output.add(line);
        }

        String cleaned = normalizeParagraphSpacing(String.join("\n", output));
        return new CleanedText(
                cleaned,
                new CleaningStats(rawText.length(), cleaned.length(), removedBreadcrumb, removedHeading, 0)
        );
    }

    private String removeOverlap(StringBuilder combined,
                                 String next,
                                 CleaningStats.Accumulator stats) {
        String normalizedNext = normalizeParagraphSpacing(next);
        if (combined.isEmpty() || normalizedNext.isBlank()) {
            return normalizedNext;
        }
        String current = combined.toString();
        int max = Math.min(Math.min(MAX_OVERLAP_CHARS, current.length()), normalizedNext.length());
        for (int len = max; len >= MIN_OVERLAP_CHARS; len--) {
            String tail = current.substring(current.length() - len);
            String head = normalizedNext.substring(0, len);
            if (canonical(tail).equals(canonical(head))) {
                stats.removedOverlapCount++;
                return normalizeParagraphSpacing(normalizedNext.substring(len));
            }
        }
        return normalizedNext;
    }

    private boolean isBreadcrumbLine(String line, String title, String path) {
        String normalizedLine = normalize(line);
        String normalizedTitle = normalize(title);
        String normalizedPath = normalize(path);
        if (!normalizedPath.isBlank() && normalizedLine.equals(normalizedPath)) {
            return true;
        }
        if (!normalizedTitle.isBlank()
                && normalizedLine.endsWith(" > " + normalizedTitle)
                && normalizedLine.length() > normalizedTitle.length()) {
            return true;
        }
        return false;
    }

    private boolean isSummaryHeadingLine(String line, String title) {
        String normalizedLine = normalize(line);
        String normalizedTitle = normalize(title);
        if (!normalizedTitle.isBlank() && normalizedLine.equals(normalizedTitle)) {
            return true;
        }
        return looksLikeSummaryHeading(line);
    }

    private boolean looksLikeSummaryHeading(String line) {
        String normalized = normalize(line);
        return normalized.matches(".*\\btom tat\\b.*\\bchuong\\b.*")
                || normalized.matches(".*\\bchuong\\b.*\\btom tat\\b.*");
    }

    private String stripMarkdownHeading(String line) {
        return line == null ? "" : line.replaceFirst("^#{1,6}\\s+", "").trim();
    }

    private String normalizeParagraphSpacing(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return dedupeConsecutiveParagraphs(normalized);
    }

    private String dedupeConsecutiveParagraphs(String value) {
        String[] paragraphs = value.split("\\n\\s*\\n");
        List<String> output = new ArrayList<>();
        String previous = null;
        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            String current = canonical(trimmed);
            if (!current.equals(previous)) {
                output.add(trimmed);
            }
            previous = current;
        }
        return String.join("\n\n", output);
    }

    private String canonical(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd')
                .replace('Đ', 'D')
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}>\\s]", " ")
                .replaceAll("\\s*>\\s*", " > ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized;
    }

    private DocumentChunk copyChunkWithContent(DocumentChunk source, String content) {
        DocumentChunk copy = DocumentChunk.builder()
                .document(source.getDocument())
                .subjectId(source.getSubjectId())
                .node(source.getNode())
                .parentNode(source.getParentNode())
                .chunkIndex(source.getChunkIndex())
                .sourceOrder(source.getSourceOrder())
                .chunkType(source.getChunkType())
                .sectionPath(source.getSectionPath())
                .pageFrom(source.getPageFrom())
                .pageTo(source.getPageTo())
                .content(content)
                .tokenCount(source.getTokenCount())
                .metadataJsonb(source.getMetadataJsonb() == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(source.getMetadataJsonb()))
                .build();
        copy.setId(source.getId());
        return copy;
    }

    public record CleanedOriginalSummary(
            String cleanedText,
            List<DocumentChunk> cleanedChunks,
            CleaningStats stats
    ) {
        public CleanedOriginalSummary {
            cleanedText = cleanedText == null ? "" : cleanedText;
            cleanedChunks = cleanedChunks == null ? List.of() : List.copyOf(cleanedChunks);
        }
    }

    public record CleanedText(String text, CleaningStats stats) {
        public CleanedText {
            text = text == null ? "" : text;
        }
    }

    public record CleaningStats(
            int originalChars,
            int cleanedChars,
            int removedBreadcrumbCount,
            int removedHeadingCount,
            int removedOverlapCount
    ) {
        static CleaningStats empty(String value) {
            int chars = value == null ? 0 : value.length();
            return new CleaningStats(chars, chars, 0, 0, 0);
        }

        private static final class Accumulator {
            private int removedBreadcrumbCount;
            private int removedHeadingCount;
            private int removedOverlapCount;

            private void add(CleaningStats stats) {
                if (stats == null) {
                    return;
                }
                removedBreadcrumbCount += stats.removedBreadcrumbCount();
                removedHeadingCount += stats.removedHeadingCount();
                removedOverlapCount += stats.removedOverlapCount();
            }
        }
    }
}
