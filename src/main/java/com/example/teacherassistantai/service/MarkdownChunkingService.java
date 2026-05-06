package com.example.teacherassistantai.service;

import com.example.teacherassistantai.exception.InvalidDataException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class MarkdownChunkingService {

    private static final int TARGET_SIZE = 800;
    private static final int MAX_CHUNK_SIZE = 1500;
    private static final int OVERLAP_SIZE = 200;
    private static final int MIN_CHUNK_SIZE = 500;
    private static final int TABLE_MAX_SIZE = 3200;
    private static final int FILE_SIZE_GATE_BYTES = 100 * 1024;

    private static final Pattern HEADER_PATTERN = Pattern.compile("^(#{1,6})\\s+.+$");
    private static final Pattern TABLE_LINE_PATTERN = Pattern.compile("^\\s*\\|.*\\|\\s*$");
    private static final Pattern MARKDOWN_HEADER_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern PAGE_MARKER_PATTERN = Pattern.compile("^<!--\\s*page:\\s*(\\d+)\\s*-->$");
    private static final Pattern PART_TITLE_PATTERN = Pattern.compile("(?iu)^phần\\s+[ivxlcdm]+\\b.*$");
    private static final Pattern CHAPTER_TITLE_PATTERN = Pattern.compile("(?iu)^chương\\s+([0-9ivxlcdm]+|nhập\\s+môn)\\b.*$");
    private static final Pattern ROMAN_SECTION_PATTERN = Pattern.compile("(?iu)^[ivxlcdm]+[.-]\\s+.+$");
    private static final Pattern DECIMAL_SECTION_PATTERN = Pattern.compile("^\\d+(?:\\.\\d+)+\\.?\\s+.+$");
    private static final Pattern SINGLE_NUMBERED_PATTERN = Pattern.compile("^\\d{1,2}\\.\\s+.+$");

    public List<String> chunk(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            throw new InvalidDataException("Markdown content is empty");
        }

        List<HierarchicalMarkdownChunk> hierarchicalChunks = chunkHierarchical(markdown);
        if (!hierarchicalChunks.isEmpty()) {
            return hierarchicalChunks.stream()
                    .map(HierarchicalMarkdownChunk::content)
                    .toList();
        }

        List<String> normalizedInputs = preSplitLargeInput(markdown);
        List<String> chunks = new ArrayList<>();
        for (String input : normalizedInputs) {
            chunks.addAll(chunkStructured(input));
        }
        return chunks;
    }

    public List<HierarchicalMarkdownChunk> chunkHierarchical(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            throw new InvalidDataException("Markdown content is empty");
        }

        return parseHierarchicalDocument(markdown).chunks();
    }

    public HierarchicalMarkdownDocument parseHierarchicalDocument(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            throw new InvalidDataException("Markdown content is empty");
        }

        String normalized = normalizeForHierarchy(markdown);
        HierarchyNode root = parseHierarchy(normalized);
        List<HierarchicalMarkdownChunk> chunks = new ArrayList<>();
        emitHierarchicalChunks(root, chunks);
        if (chunks.isEmpty()) {
            chunks.addAll(flatFallbackChunks(markdown));
        }
        return new HierarchicalMarkdownDocument(normalized, toPublicNode(root), chunks);
    }

    private List<HierarchicalMarkdownChunk> flatFallbackChunks(String markdown) {
        List<String> flatChunks = new ArrayList<>();
        for (String input : preSplitLargeInput(markdown)) {
            flatChunks.addAll(chunkStructured(input));
        }
        List<HierarchicalMarkdownChunk> chunks = new ArrayList<>();
        for (int i = 0; i < flatChunks.size(); i++) {
            String content = flatChunks.get(i);
            chunks.add(new HierarchicalMarkdownChunk(
                    content,
                    "TEXT",
                    "body",
                    "flat-" + (i + 1),
                    null,
                    null,
                    List.of(),
                    null,
                    null,
                    null,
                    null
            ));
        }
        return chunks;
    }

    public String normalizeMarkdownForHierarchy(String markdown) {
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        List<String> output = new ArrayList<>();
        List<String> lines = normalized.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String rawLine = lines.get(i);
            String line = rawLine.stripTrailing();
            SplitHeading split = splitHeadingWithAttachedBody(line);
            if (split != null) {
                output.add(split.heading());
                output.add("");
                output.add(split.body());
            } else {
                output.add(line);
            }
        }
        return repairBrokenHeadings(String.join("\n", output).replaceAll("\\n{4,}", "\n\n\n")).trim();
    }

    private String normalizeForHierarchy(String markdown) {
        return normalizeMarkdownForHierarchy(markdown);
    }

    private String repairBrokenHeadings(String markdown) {
        String[] lines = markdown.split("\\n", -1);
        List<String> output = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String next = nextNonBlank(lines, i + 1);
            if (isIncompleteMarkdownHeading(line) && next != null && isHeadingContinuation(next)) {
                int nextIndex = nextNonBlankIndex(lines, i + 1);
                output.add(line.stripTrailing() + " " + next.trim());
                i = nextIndex;
                continue;
            }
            output.add(line);
        }
        return String.join("\n", output);
    }

    private boolean isIncompleteMarkdownHeading(String line) {
        java.util.regex.Matcher matcher = MARKDOWN_HEADER_PATTERN.matcher(line == null ? "" : line.trim());
        if (!matcher.matches()) {
            return false;
        }
        String title = matcher.group(2).trim();
        if (title.length() > 160 || title.matches(".*[.!?:;]$")) {
            return false;
        }
        String lower = title.toLowerCase(Locale.ROOT);
        return lower.endsWith(" của")
                || lower.endsWith(" cách")
                || lower.endsWith(" công")
                || lower.endsWith(" xây dựng")
                || lower.endsWith(" chống")
                || lower.endsWith(" đổi")
                || lower.endsWith(" hội")
                || lower.endsWith(" kết")
                || lower.endsWith(" thống")
                || lower.endsWith(" cơ")
                || lower.endsWith(" hiến")
                || lower.endsWith(" tác")
                || lower.endsWith(" biện")
                || lower.endsWith(" chủ")
                || lower.endsWith(" nguồn")
                || lower.endsWith(" phạm")
                || lower.endsWith(" nghĩa")
                || lower.endsWith(" việt");
    }

    private boolean isHeadingContinuation(String line) {
        if (line == null || line.isBlank() || line.length() > 120) {
            return false;
        }
        if (MARKDOWN_HEADER_PATTERN.matcher(line.trim()).matches() || PAGE_MARKER_PATTERN.matcher(line.trim()).matches()) {
            return false;
        }
        return !line.trim().matches("^[-+*]\\s+.+$");
    }

    private String nextNonBlank(String[] lines, int start) {
        int index = nextNonBlankIndex(lines, start);
        return index >= 0 ? lines[index] : null;
    }

    private int nextNonBlankIndex(String[] lines, int start) {
        for (int i = start; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                return i;
            }
        }
        return -1;
    }

    private SplitHeading splitHeadingWithAttachedBody(String line) {
        java.util.regex.Matcher matcher = MARKDOWN_HEADER_PATTERN.matcher(line);
        if (!matcher.matches() || line.length() <= 90) {
            return null;
        }

        String hashes = matcher.group(1);
        String title = matcher.group(2).trim();
        if (!isSplittableLongHeading(title)) {
            return null;
        }

        for (String marker : List.of(
                " Từ ",
                " Trong ",
                " Theo ",
                " Hiện thực ",
                " Khả năng ",
                " Phép ",
                " Hoạt động ",
                " Với tư cách ",
                " Sản xuất ",
                " Xã hội ",
                " Chức năng ",
                " Nguyên nhân ",
                " Các cuộc ",
                " Những hình thái ",
                " Trước Mác, ",
                " Để cho ",
                " 1. ")) {
            int index = title.indexOf(marker);
            if (index >= 20 && index < title.length() - 20) {
                return new SplitHeading(hashes + " " + title.substring(0, index).trim(),
                        title.substring(index + 1).trim());
            }
        }
        return null;
    }

    private boolean isSplittableLongHeading(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        return CHAPTER_TITLE_PATTERN.matcher(lower).matches()
                || DECIMAL_SECTION_PATTERN.matcher(title).matches()
                || SINGLE_NUMBERED_PATTERN.matcher(title).matches()
                || ROMAN_SECTION_PATTERN.matcher(lower).matches();
    }

    private HierarchyNode parseHierarchy(String markdown) {
        HierarchyNode root = new HierarchyNode("n0", null, 0, "document", "Document", 0);
        Deque<HierarchyNode> stack = new ArrayDeque<>();
        stack.push(root);

        String[] lines = markdown.split("\\n", -1);
        int offset = 0;
        int nodeSequence = 1;
        boolean inSpecialNode = false;
        Integer currentPage = markdown.contains("<!-- page:") ? 1 : null;
        root.observePage(currentPage);

        for (String line : lines) {
            String trimmed = line.trim();
            java.util.regex.Matcher pageMatcher = PAGE_MARKER_PATTERN.matcher(trimmed);
            if (pageMatcher.matches()) {
                currentPage = Integer.parseInt(pageMatcher.group(1));
                stack.peek().observePage(currentPage);
                offset += line.length() + 1;
                continue;
            }

            SpecialMarker specialMarker = specialMarker(trimmed);
            if (specialMarker != null) {
                while (stack.peek().level >= 3 && stack.size() > 1) {
                    stack.pop();
                }
                HierarchyNode parent = stack.peek();
                HierarchyNode node = new HierarchyNode("n" + nodeSequence++, parent, parent.level + 1,
                        specialMarker.nodeType(), specialMarker.title(), offset);
                node.observePage(currentPage);
                parent.children.add(node);
                stack.push(node);
                inSpecialNode = true;
                offset += line.length() + 1;
                continue;
            }

            HeadingInfo heading = detectLogicalHeading(trimmed);
            if (heading != null && (!inSpecialNode || heading.type().equals("chapter") || heading.type().equals("part"))) {
                while (stack.peek().level >= heading.level() && stack.size() > 1) {
                    stack.pop();
                }
                HierarchyNode parent = stack.peek();
                HierarchyNode node = new HierarchyNode("n" + nodeSequence++, parent, heading.level(),
                        heading.type(), heading.title(), offset);
                node.observePage(currentPage);
                parent.children.add(node);
                stack.push(node);
                inSpecialNode = heading.type().equals("summary") || heading.type().equals("review_questions");
            } else {
                stack.peek().appendContent(line, offset, currentPage);
            }
            offset += line.length() + 1;
        }

        root.close(markdown.length());
        return root;
    }

    private HeadingInfo detectLogicalHeading(String line) {
        java.util.regex.Matcher matcher = MARKDOWN_HEADER_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return null;
        }

        String title = matcher.group(2).trim();
        String lower = title.toLowerCase(Locale.ROOT);
        if (isReviewQuestionHeading(title)) {
            return null;
        }
        if (PART_TITLE_PATTERN.matcher(lower).matches()) {
            return new HeadingInfo(1, "part", title);
        }
        if (CHAPTER_TITLE_PATTERN.matcher(lower).matches()) {
            return new HeadingInfo(2, "chapter", title);
        }
        if (ROMAN_SECTION_PATTERN.matcher(lower).matches()) {
            return new HeadingInfo(3, "section", title);
        }
        if (DECIMAL_SECTION_PATTERN.matcher(title).matches()) {
            int depth = title.replaceFirst("^([0-9.]+).*", "$1").split("\\.").length;
            return new HeadingInfo(Math.min(6, 2 + depth), depth >= 3 ? "subsection" : "section", title);
        }
        if (SINGLE_NUMBERED_PATTERN.matcher(title).matches()) {
            return new HeadingInfo(4, "subsection", title);
        }
        return null;
    }

    private boolean isReviewQuestionHeading(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        return lower.matches("^\\d{1,2}\\.\\s+.+\\?$")
                || lower.contains("anh (chị)")
                || lower.startsWith("tại sao ")
                || lower.startsWith("vì sao ")
                || lower.startsWith("trình bày ")
                || lower.startsWith("phân tích ")
                || lower.startsWith("nêu ")
                || lower.startsWith("khái quát ");
    }

    private SpecialMarker specialMarker(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        String withoutHeading = line.replaceFirst("^#{1,6}\\s+", "");
        lower = withoutHeading.toLowerCase(Locale.ROOT);
        if (MARKDOWN_HEADER_PATTERN.matcher(line).matches() && isReviewQuestionHeading(withoutHeading)) {
            return new SpecialMarker("review_questions", withoutHeading);
        }
        if (lower.matches("(?iu)^tóm\\s+tắt\\s+chương.*$")) {
            return new SpecialMarker("summary", withoutHeading);
        }
        if (lower.matches("(?iu)^(câu\\s+hỏi\\s+ôn\\s+tập|nội\\s+dung\\s+ôn\\s+tập.*).*$")) {
            return new SpecialMarker("review_questions", withoutHeading);
        }
        if (lower.matches("(?iu)^kết\\s+luận$")) {
            return new SpecialMarker("section", withoutHeading);
        }
        return null;
    }

    private void emitHierarchicalChunks(HierarchyNode node, List<HierarchicalMarkdownChunk> output) {
        for (HierarchyNode child : node.children) {
            emitHierarchicalChunks(child, output);
        }

        String body = node.content.toString().trim();
        if (node.type.equals("review_questions") && !node.title.isBlank() && isReviewQuestionHeading(node.title)) {
            body = body.isBlank() ? node.title : node.title + "\n\n" + body;
        }
        if (node.parent == null || body.isBlank()) {
            return;
        }

        List<String> breadcrumb = breadcrumb(node);
        String breadcrumbText = String.join(" > ", breadcrumb);
        List<String> bodyChunks = chunkTextBlock(body);
        for (String bodyChunk : bodyChunks) {
            String content = breadcrumbText.isBlank() ? bodyChunk : breadcrumbText + "\n\n" + bodyChunk;
            output.add(new HierarchicalMarkdownChunk(
                    content,
                    chunkType(node.type),
                    node.type,
                    node.id,
                    parentNodeId(node),
                    node.title,
                    breadcrumb,
                    node.pageFrom,
                    node.pageTo,
                    node.charStart,
                    node.charEnd
            ));
        }
    }

    private String parentNodeId(HierarchyNode node) {
        HierarchyNode cursor = node.parent;
        while (cursor != null && cursor.parent != null) {
            if (cursor.type.equals("chapter") || cursor.type.equals("section")) {
                return cursor.id;
            }
            cursor = cursor.parent;
        }
        return node.parent == null ? null : node.parent.id;
    }

    private String chunkType(String nodeType) {
        return switch (nodeType) {
            case "summary" -> "SUMMARY";
            case "review_questions" -> "REVIEW_QUESTIONS";
            default -> "TEXT";
        };
    }

    private List<String> breadcrumb(HierarchyNode node) {
        List<String> values = new ArrayList<>();
        HierarchyNode cursor = node;
        while (cursor != null && cursor.parent != null) {
            values.add(cursor.title);
            cursor = cursor.parent;
        }
        Collections.reverse(values);
        return values;
    }

    private PublicHierarchyNode toPublicNode(HierarchyNode node) {
        List<PublicHierarchyNode> children = node.children.stream()
                .map(this::toPublicNode)
                .toList();
        return new PublicHierarchyNode(
                node.id,
                node.parent == null ? null : node.parent.id,
                node.type,
                node.title,
                node.parent == null ? List.of() : breadcrumb(node),
                node.pageFrom,
                node.pageTo,
                node.charStart,
                node.charEnd,
                node.content.toString().trim().length(),
                children
        );
    }

    private List<String> preSplitLargeInput(String markdown) {
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= FILE_SIZE_GATE_BYTES) {
            return List.of(markdown);
        }

        List<String> coarseParts = new ArrayList<>();
        int start = 0;
        while (start < markdown.length()) {
            int hardEnd = Math.min(start + MAX_CHUNK_SIZE, markdown.length());
            int split = findBestSplit(markdown, start, hardEnd);
            coarseParts.add(markdown.substring(start, split).trim());
            start = split;
        }
        return coarseParts;
    }

    private List<String> chunkStructured(String markdown) {
        List<Section> sections = splitByHeaders(markdown);
        List<String> output = new ArrayList<>();

        for (Section section : sections) {
            List<String> sectionChunks = new ArrayList<>();
            List<Block> blocks = splitBlocks(section.content());

            for (Block block : blocks) {
                if (block.type == BlockType.TABLE) {
                    sectionChunks.addAll(chunkTable(block.content));
                } else {
                    sectionChunks.addAll(chunkTextBlock(block.content));
                }
            }

            output.addAll(applySectionMinSizeRule(sectionChunks));
        }

        return output;
    }

    private List<Section> splitByHeaders(String markdown) {
        String[] lines = markdown.split("\\R", -1);
        List<Section> sections = new ArrayList<>();

        String currentHeader = "";
        StringBuilder currentContent = new StringBuilder();
        boolean sawHeader = false;

        for (String line : lines) {
            if (HEADER_PATTERN.matcher(line).matches()) {
                sawHeader = true;
                if (!currentContent.isEmpty()) {
                    sections.add(new Section(currentHeader, currentContent.toString().trim()));
                    currentContent.setLength(0);
                }
                currentHeader = line.trim();
                currentContent.append(currentHeader).append("\n");
                continue;
            }
            currentContent.append(line).append("\n");
        }

        if (!currentContent.isEmpty()) {
            sections.add(new Section(currentHeader, currentContent.toString().trim()));
        }

        if (!sawHeader && sections.isEmpty()) {
            return List.of(new Section("", markdown));
        }
        return sections;
    }

    private List<Block> splitBlocks(String sectionText) {
        List<Block> blocks = new ArrayList<>();
        String[] lines = sectionText.split("\\R", -1);

        StringBuilder textBuffer = new StringBuilder();
        StringBuilder tableBuffer = new StringBuilder();
        boolean inTable = false;

        for (String line : lines) {
            boolean isTableLine = TABLE_LINE_PATTERN.matcher(line).matches();
            if (isTableLine) {
                if (!inTable && !textBuffer.isEmpty()) {
                    blocks.add(new Block(BlockType.TEXT, textBuffer.toString().trim()));
                    textBuffer.setLength(0);
                }
                inTable = true;
                tableBuffer.append(line).append("\n");
            } else {
                if (inTable) {
                    blocks.add(new Block(BlockType.TABLE, tableBuffer.toString().trim()));
                    tableBuffer.setLength(0);
                    inTable = false;
                }
                textBuffer.append(line).append("\n");
            }
        }

        if (!tableBuffer.isEmpty()) {
            blocks.add(new Block(BlockType.TABLE, tableBuffer.toString().trim()));
        }
        if (!textBuffer.isEmpty()) {
            blocks.add(new Block(BlockType.TEXT, textBuffer.toString().trim()));
        }

        return blocks;
    }

    private List<String> chunkTable(String table) {
        if (table.length() <= TABLE_MAX_SIZE) {
            return List.of(table);
        }

        String[] rows = table.split("\\R");
        if (rows.length <= 2) {
            return splitLongSegment(table);
        }

        String header = rows[0] + "\n" + rows[1] + "\n";
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);

        for (int i = 2; i < rows.length; i++) {
            String row = rows[i];
            if (current.length() + row.length() + 1 > MAX_CHUNK_SIZE) {
                chunks.add(current.toString().trim());
                current = new StringBuilder(header).append(row).append("\n");
            } else {
                current.append(row).append("\n");
            }
        }

        if (!current.toString().isBlank()) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private List<String> chunkTextBlock(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String[] paragraphs = text.split("\\n\\s*\\n");
        List<String> normalizedParagraphs = new ArrayList<>();
        for (String paragraph : paragraphs) {
            if (paragraph == null || paragraph.isBlank()) continue;
            String value = paragraph.trim();
            if (value.length() > MAX_CHUNK_SIZE) {
                normalizedParagraphs.addAll(splitLongSegment(value));
            } else {
                normalizedParagraphs.add(value);
            }
        }

        return combineParagraphs(normalizedParagraphs);
    }

    private List<String> combineParagraphs(List<String> paragraphs) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (current.isEmpty()) {
                current.append(paragraph);
                continue;
            }

            int candidateLength = current.length() + 2 + paragraph.length();
            if (candidateLength <= TARGET_SIZE) {
                current.append("\n\n").append(paragraph);
                continue;
            }

            chunks.add(current.toString().trim());
            String overlap = tail(chunks.getLast(), OVERLAP_SIZE);
            current = new StringBuilder();
            if (!overlap.isBlank()) {
                current.append(overlap).append("\n");
            }
            current.append(paragraph);

            while (current.length() > MAX_CHUNK_SIZE) {
                int split = findBestSplit(current.toString(), 0, Math.min(MAX_CHUNK_SIZE, current.length()));
                String head = current.substring(0, split).trim();
                chunks.add(head);
                String next = tail(head, OVERLAP_SIZE) + "\n" + current.substring(split).trim();
                current = new StringBuilder(next.trim());
            }
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private List<String> splitLongSegment(String value) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < value.length()) {
            int hardEnd = Math.min(start + MAX_CHUNK_SIZE, value.length());
            int split = findBestSplit(value, start, hardEnd);
            chunks.add(value.substring(start, split).trim());
            if (split >= value.length()) break;
            start = Math.max(start + 1, split - OVERLAP_SIZE);
        }
        return chunks;
    }

    private int findBestSplit(String text, int start, int hardEnd) {
        if (hardEnd >= text.length()) {
            return text.length();
        }

        int best = -1;
        for (int i = hardEnd; i > start + 200; i--) {
            char c = text.charAt(i - 1);
            if (c == '.' || c == '!' || c == '?' || c == '\n' || c == ';') {
                best = i;
                break;
            }
        }

        if (best == -1) {
            for (int i = hardEnd; i > start + 200; i--) {
                if (Character.isWhitespace(text.charAt(i - 1))) {
                    best = i;
                    break;
                }
            }
        }

        return best == -1 ? hardEnd : best;
    }

    private List<String> applySectionMinSizeRule(List<String> sectionChunks) {
        if (sectionChunks.isEmpty()) {
            return sectionChunks;
        }

        List<String> filtered = new ArrayList<>();
        for (int i = 0; i < sectionChunks.size(); i++) {
            String chunk = sectionChunks.get(i);
            boolean isLast = i == sectionChunks.size() - 1;
            if (!isLast && chunk.length() < MIN_CHUNK_SIZE) {
                continue;
            }
            filtered.add(chunk);
        }
        if (filtered.isEmpty()) {
            filtered.add(sectionChunks.getLast());
        }
        return filtered;
    }

    private String tail(String input, int size) {
        if (input == null || input.isBlank()) return "";
        if (input.length() <= size) return input;
        return input.substring(input.length() - size);
    }

    private record Section(String header, String content) {
    }

    private enum BlockType {
        TEXT,
        TABLE
    }

    private record Block(BlockType type, String content) {
    }

    private record HeadingInfo(int level, String type, String title) {
    }

    private record SpecialMarker(String nodeType, String title) {
    }

    private record SplitHeading(String heading, String body) {
    }

    public record HierarchicalMarkdownDocument(
            String normalizedMarkdown,
            PublicHierarchyNode root,
            List<HierarchicalMarkdownChunk> chunks
    ) {
    }

    public record PublicHierarchyNode(
            String nodeId,
            String parentNodeId,
            String nodeType,
            String title,
            List<String> breadcrumb,
            Integer pageFrom,
            Integer pageTo,
            Integer charStart,
            Integer charEnd,
            Integer contentCharCount,
            List<PublicHierarchyNode> children
    ) {
    }

    private static final class HierarchyNode {
        private final String id;
        private final HierarchyNode parent;
        private final int level;
        private final String type;
        private final String title;
        private final int charStart;
        private int charEnd;
        private Integer pageFrom;
        private Integer pageTo;
        private final StringBuilder content = new StringBuilder();
        private final List<HierarchyNode> children = new ArrayList<>();

        private HierarchyNode(String id,
                              HierarchyNode parent,
                              int level,
                              String type,
                              String title,
                              int charStart) {
            this.id = id;
            this.parent = parent;
            this.level = level;
            this.type = type;
            this.title = title;
            this.charStart = charStart;
            this.charEnd = charStart;
        }

        private void appendContent(String line, int offset, Integer page) {
            if (content.isEmpty() && line.isBlank()) {
                return;
            }
            observePage(page);
            if (content.isEmpty()) {
                charEnd = offset;
            }
            content.append(line).append('\n');
            charEnd = offset + line.length();
        }

        private void observePage(Integer page) {
            if (page == null) {
                return;
            }
            if (pageFrom == null || page < pageFrom) {
                pageFrom = page;
            }
            if (pageTo == null || page > pageTo) {
                pageTo = page;
            }
        }

        private void close(int fallbackEnd) {
            if (charEnd <= charStart) {
                charEnd = fallbackEnd;
            }
            for (HierarchyNode child : children) {
                child.close(fallbackEnd);
            }
        }
    }
}
