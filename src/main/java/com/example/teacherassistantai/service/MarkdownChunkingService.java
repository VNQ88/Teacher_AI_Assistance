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
    private static final Pattern PART_TITLE_PATTERN = Pattern.compile("(?iu)^phần\\s+([ivxlcdm]+)\\b.*$");
    private static final Pattern CHAPTER_TITLE_PATTERN = Pattern.compile("(?iu)^chương\\s+([0-9ivxlcdm]+|nhập\\s+môn)\\b.*$");
    private static final Pattern ROMAN_SECTION_PATTERN = Pattern.compile("(?iu)^[ivxlcdm]+[.-]\\s+.+$");
    private static final Pattern DECIMAL_SECTION_PATTERN = Pattern.compile("^\\d+(?:\\.\\d+)+\\.?\\s+.+$");
    private static final Pattern SINGLE_NUMBERED_PATTERN = Pattern.compile("^\\d{1,2}\\.\\s+.+$");
    private static final Pattern REVIEW_HEADING_PATTERN = Pattern.compile("(?iu)^(câu\\s+hỏi\\s+ôn\\s+tập|nội\\s+dung\\s+ôn\\s+tập.*).*$");
    private static final Pattern SUMMARY_HEADING_PATTERN = Pattern.compile("(?iu)^tóm\\s+tắt\\s+chương.*$");
    private static final Pattern CONCLUSION_HEADING_PATTERN = Pattern.compile("(?iu)^kết\\s+luận$");
    private static final Pattern LIST_LEAD_IN_PATTERN = Pattern.compile("(?iu).*(bao\\s+g[oồ]m|như\\s+sau|g[oồ]m|sau\\s+đây)\\s*:\\s*$");
    private static final Pattern CITATION_NUMBERED_LINE_PATTERN = Pattern.compile("^\\d{1,3}\\.?\\s+\\S.+$");
    private static final Pattern CITATION_STRONG_SIGNAL_PATTERN = Pattern.compile("(?iu).*(nxb\\.?|toàn\\s+tập|văn\\s+kiện|\\btrang\\s+\\d|\\btr\\.?\\s*\\d|\\bt\\.?\\s*\\d|sđd|dẫn\\s+theo|xem\\s+sđd).*");
    private static final Pattern CITATION_CONTINUATION_PATTERN = Pattern.compile("(?iu)^(trang\\s+\\d|tr\\.?\\s*\\d|t\\.?\\s*\\d|nxb\\.?\\s+.+|hà\\s+nội[,\\s].+|mátxcơva[,\\s].+|số\\s+\\d.+|ngày\\s+\\d.+|\\(tiếng\\s+nga\\).*)$");
    private static final String CITATION_CHUNK_TYPE = "CITATION";
    private static final String TEXT_CHUNK_TYPE = "TEXT";
    private static final String DEFAULT_ROOT_TITLE = "Document";

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
        return parseHierarchicalDocument(markdown, null);
    }

    public HierarchicalMarkdownDocument parseHierarchicalDocument(String markdown, String documentTitle) {
        if (markdown == null || markdown.isBlank()) {
            throw new InvalidDataException("Markdown content is empty");
        }

        String normalized = normalizeForHierarchy(markdown);
        HierarchyNode root = parseHierarchy(normalized, rootTitle(markdown, documentTitle));
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
        String repaired = repairBrokenHeadings(String.join("\n", output).replaceAll("\\n{4,}", "\n\n\n"));
        return demoteInvalidMarkdownHeadings(repaired).trim();
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

    private String demoteInvalidMarkdownHeadings(String markdown) {
        String[] lines = markdown.split("\\n", -1);
        List<String> output = new ArrayList<>();
        boolean inSingleNumberListContext = false;
        boolean inSpecialNodeContext = false;

        for (String line : lines) {
            String trimmed = line.trim();
            java.util.regex.Matcher headingMatcher = MARKDOWN_HEADER_PATTERN.matcher(trimmed);
            if (!headingMatcher.matches()) {
                output.add(line);
                if (isSpecialNodeTitle(trimmed)) {
                    inSpecialNodeContext = true;
                    inSingleNumberListContext = false;
                } else if (isStructuredHeadingTitle(trimmed) && !isSingleNumberedTitle(trimmed)) {
                    inSpecialNodeContext = false;
                    inSingleNumberListContext = false;
                }
                continue;
            }

            String hashes = headingMatcher.group(1);
            String title = headingMatcher.group(2).trim();
            String previous = previousNonBlank(output);

            if (isSpecialNodeTitle(title)) {
                output.add(hashes + " " + title);
                inSpecialNodeContext = true;
                inSingleNumberListContext = false;
                continue;
            }

            if (isSingleNumberedTitle(title)) {
                if (inSpecialNodeContext || inSingleNumberListContext || isListLeadIn(previous)) {
                    output.add(title);
                    inSingleNumberListContext = true;
                    continue;
                }
                if (isReviewQuestionHeading(title)) {
                    output.add(hashes + " " + title);
                    continue;
                }
                output.add(hashes + " " + title);
                continue;
            }

            if (isStructuredHeadingTitle(title)) {
                output.add(hashes + " " + title);
                inSpecialNodeContext = false;
                inSingleNumberListContext = false;
                continue;
            }

            output.add(title);
        }

        return String.join("\n", output);
    }

    private String previousNonBlank(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String value = lines.get(i).trim();
            if (!value.isBlank() && !PAGE_MARKER_PATTERN.matcher(value).matches()) {
                return stripMarkdownHeading(value);
            }
        }
        return null;
    }

    private String stripMarkdownHeading(String value) {
        if (value == null) {
            return null;
        }
        java.util.regex.Matcher matcher = MARKDOWN_HEADER_PATTERN.matcher(value.trim());
        return matcher.matches() ? matcher.group(2).trim() : value.trim();
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
        String trimmed = line.trim();
        if (MARKDOWN_HEADER_PATTERN.matcher(trimmed).matches() || PAGE_MARKER_PATTERN.matcher(trimmed).matches()) {
            return false;
        }
        if (isStructuredHeadingTitle(trimmed) || isSpecialNodeTitle(trimmed)) {
            return false;
        }
        return !trimmed.matches("^[-+*]\\s+.+$");
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
        return isChapterTitle(title)
                || isSupportedDecimalHeading(title)
                || SINGLE_NUMBERED_PATTERN.matcher(title).matches()
                || isRomanSectionTitle(title);
    }

    private String rootTitle(String markdown, String documentTitle) {
        if (documentTitle != null && !documentTitle.isBlank()) {
            return documentTitle.trim();
        }
        String inferred = firstH1Title(markdown);
        return inferred == null || inferred.isBlank() ? DEFAULT_ROOT_TITLE : inferred;
    }

    private String firstH1Title(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return null;
        }
        for (String line : markdown.lines().toList()) {
            java.util.regex.Matcher matcher = Pattern.compile("^#\\s+(.+)$").matcher(line.trim());
            if (matcher.matches()) {
                return matcher.group(1).trim();
            }
        }
        return null;
    }

    private boolean isStructuredHeadingTitle(String title) {
        return isPartTitle(title)
                || isChapterTitle(title)
                || isRomanSectionTitle(title)
                || isSupportedDecimalHeading(title)
                || CONCLUSION_HEADING_PATTERN.matcher(normalizeTitle(title)).matches();
    }

    private boolean isSpecialNodeTitle(String title) {
        String normalized = normalizeTitle(title);
        return SUMMARY_HEADING_PATTERN.matcher(normalized).matches()
                || REVIEW_HEADING_PATTERN.matcher(normalized).matches();
    }

    private boolean isPartTitle(String title) {
        java.util.regex.Matcher matcher = PART_TITLE_PATTERN.matcher(normalizeTitle(title));
        return matcher.matches() && isRomanNumeral(matcher.group(1));
    }

    private boolean isChapterTitle(String title) {
        return CHAPTER_TITLE_PATTERN.matcher(normalizeTitle(title)).matches();
    }

    private boolean isRomanSectionTitle(String title) {
        return ROMAN_SECTION_PATTERN.matcher(normalizeTitle(title)).matches();
    }

    private boolean isSupportedDecimalHeading(String title) {
        int depth = decimalDepth(title);
        return depth >= 2 && depth <= 4;
    }

    private int decimalDepth(String title) {
        if (title == null) {
            return 0;
        }
        java.util.regex.Matcher matcher = Pattern.compile("^(\\d+(?:\\.\\d+)+)\\.?\\s+.+$").matcher(title.trim());
        if (!matcher.matches()) {
            return 0;
        }
        return matcher.group(1).split("\\.").length;
    }

    private boolean isSingleNumberedTitle(String title) {
        return SINGLE_NUMBERED_PATTERN.matcher(title == null ? "" : title.trim()).matches();
    }

    private boolean isListLeadIn(String value) {
        return value != null && LIST_LEAD_IN_PATTERN.matcher(value.trim()).matches();
    }

    private boolean isRomanNumeral(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return token.trim().matches("(?iu)^(i|ii|iii|iv|v|vi|vii|viii|ix|x)$");
    }

    private String normalizeTitle(String title) {
        return title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
    }

    private HierarchyNode parseHierarchy(String markdown, String rootTitle) {
        HierarchyNode root = new HierarchyNode("n0", null, 0, "document", rootTitle, 0);
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
                inSpecialNode = node.type.equals("summary") || node.type.equals("review_questions");
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
        if (isReviewQuestionHeading(title)) {
            return null;
        }
        if (isPartTitle(title)) {
            return new HeadingInfo(1, "part", title);
        }
        if (isChapterTitle(title)) {
            return new HeadingInfo(2, "chapter", title);
        }
        if (isRomanSectionTitle(title)) {
            return new HeadingInfo(3, "section", title);
        }
        if (DECIMAL_SECTION_PATTERN.matcher(title).matches()) {
            int depth = decimalDepth(title);
            if (depth == 2) {
                return new HeadingInfo(3, "section", title);
            }
            if (depth == 3) {
                return new HeadingInfo(4, "subsection", title);
            }
            if (depth == 4) {
                return new HeadingInfo(5, "subsection_level2", title);
            }
            return null;
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
        if (SUMMARY_HEADING_PATTERN.matcher(lower).matches()) {
            return new SpecialMarker("summary", withoutHeading);
        }
        if (REVIEW_HEADING_PATTERN.matcher(lower).matches()) {
            return new SpecialMarker("review_questions", withoutHeading);
        }
        if (CONCLUSION_HEADING_PATTERN.matcher(lower).matches()) {
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
        if (node.type.equals("summary") || node.type.equals("review_questions")) {
            body = stripMarkdownHeadingMarkers(body);
        }
        if (node.parent == null || body.isBlank()) {
            return;
        }

        List<String> breadcrumb = breadcrumb(node);
        String breadcrumbText = String.join(" > ", breadcrumb);
        for (BodySegment segment : splitCitationSegments(body)) {
            String segmentChunkType = CITATION_CHUNK_TYPE.equals(segment.chunkType())
                    ? CITATION_CHUNK_TYPE
                    : chunkType(node.type);
            List<String> bodyChunks = chunkTextBlock(segment.content());
            for (String bodyChunk : bodyChunks) {
                String content = breadcrumbText.isBlank() ? bodyChunk : breadcrumbText + "\n\n" + bodyChunk;
                String embedText = bodyChunk;
                output.add(new HierarchicalMarkdownChunk(
                        content,
                        embedText,
                        segmentChunkType,
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
    }

    private String stripMarkdownHeadingMarkers(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        List<String> output = new ArrayList<>();
        for (String line : content.split("\\n", -1)) {
            java.util.regex.Matcher matcher = MARKDOWN_HEADER_PATTERN.matcher(line.trim());
            output.add(matcher.matches() ? matcher.group(2).trim() : line);
        }
        return String.join("\n", output).trim();
    }

    private List<BodySegment> splitCitationSegments(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        List<String> lines = java.util.Arrays.asList(body.split("\\n", -1));
        boolean[] citationLines = classifyCitationLines(lines);

        List<BodySegment> segments = new ArrayList<>();
        String currentType = null;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String type = citationLines[i] ? CITATION_CHUNK_TYPE : TEXT_CHUNK_TYPE;
            if (currentType == null) {
                currentType = type;
            } else if (!currentType.equals(type)) {
                addBodySegment(segments, currentType, current);
                current.setLength(0);
                currentType = type;
            }
            current.append(lines.get(i)).append('\n');
        }
        addBodySegment(segments, currentType == null ? TEXT_CHUNK_TYPE : currentType, current);
        return segments;
    }

    private boolean[] classifyCitationLines(List<String> lines) {
        boolean[] citationLines = new boolean[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            if (isStrongCitationLine(lines.get(i).trim())) {
                citationLines[i] = true;
            }
        }

        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < lines.size(); i++) {
                if (citationLines[i]) {
                    continue;
                }
                String line = lines.get(i).trim();
                if (line.isBlank()) {
                    int previous = previousNonBlankIndex(lines, i - 1);
                    int next = nextNonBlankIndex(lines, i + 1);
                    if (isCitationIndex(citationLines, previous) && isCitationIndex(citationLines, next)) {
                        citationLines[i] = true;
                        changed = true;
                    }
                    continue;
                }

                int previous = previousNonBlankIndex(lines, i - 1);
                int next = nextNonBlankIndex(lines, i + 1);
                if (isCitationContinuationLine(line) && isCitationIndex(citationLines, previous)) {
                    citationLines[i] = true;
                    changed = true;
                    continue;
                }
                if (isWeakFootnoteLine(line)
                        && (isHighNumberFootnoteLine(line)
                        || isCitationIndex(citationLines, previous)
                        || isCitationIndex(citationLines, next))) {
                    citationLines[i] = true;
                    changed = true;
                }
            }
        } while (changed);
        return citationLines;
    }

    private boolean isStrongCitationLine(String line) {
        return isWeakFootnoteLine(line) && CITATION_STRONG_SIGNAL_PATTERN.matcher(line).matches();
    }

    private boolean isWeakFootnoteLine(String line) {
        return line != null
                && CITATION_NUMBERED_LINE_PATTERN.matcher(line.trim()).matches()
                && !isReviewQuestionHeading(line.trim());
    }

    private boolean isHighNumberFootnoteLine(String line) {
        Integer number = leadingNumber(line);
        return number != null && number >= 20;
    }

    private Integer leadingNumber(String line) {
        if (line == null) {
            return null;
        }
        java.util.regex.Matcher matcher = Pattern.compile("^(\\d{1,3})\\.?\\s+\\S.+$").matcher(line.trim());
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean isCitationContinuationLine(String line) {
        return line != null && CITATION_CONTINUATION_PATTERN.matcher(line.trim()).matches();
    }

    private int previousNonBlankIndex(List<String> lines, int startIndex) {
        for (int i = startIndex; i >= 0; i--) {
            if (!lines.get(i).trim().isBlank()) {
                return i;
            }
        }
        return -1;
    }

    private int nextNonBlankIndex(List<String> lines, int startIndex) {
        for (int i = startIndex; i < lines.size(); i++) {
            if (!lines.get(i).trim().isBlank()) {
                return i;
            }
        }
        return -1;
    }

    private boolean isCitationIndex(boolean[] citationLines, int index) {
        return index >= 0 && index < citationLines.length && citationLines[index];
    }

    private void addBodySegment(List<BodySegment> segments, String chunkType, StringBuilder content) {
        String value = content.toString().trim();
        if (!value.isBlank()) {
            segments.add(new BodySegment(chunkType, value));
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
            start = Math.max(start + 1, overlapStart(value, split, OVERLAP_SIZE));
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
        return input.substring(overlapStart(input, input.length(), size));
    }

    private int overlapStart(String input, int endExclusive, int size) {
        int start = Math.max(0, endExclusive - size);
        if (start == 0) {
            return start;
        }
        int cursor = start;
        while (cursor < endExclusive && !Character.isWhitespace(input.charAt(cursor))) {
            cursor++;
        }
        while (cursor < endExclusive && Character.isWhitespace(input.charAt(cursor))) {
            cursor++;
        }
        return cursor >= endExclusive ? start : cursor;
    }

    private record Section(String header, String content) {
    }

    private enum BlockType {
        TEXT,
        TABLE
    }

    private record Block(BlockType type, String content) {
    }

    private record BodySegment(String chunkType, String content) {
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
