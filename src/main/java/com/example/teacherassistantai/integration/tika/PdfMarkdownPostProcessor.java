package com.example.teacherassistantai.integration.tika;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PdfMarkdownPostProcessor {

    private static final Pattern PAGE_NUMBER_PATTERN = Pattern.compile("^\\d{1,4}$");
    private static final Pattern CHAPTER_PATTERN = Pattern.compile("(?iu)^chuong\\s+([0-9ivxlcdm]+|nhap\\s+mon)\\b\\s*(?:[:.]\\s*.*)?$");
    private static final Pattern PART_PATTERN = Pattern.compile("(?iu)^phan\\s+[ivxlcdm]+\\b.*$");
    private static final Pattern ROMAN_SECTION_PATTERN = Pattern.compile("^[IVXLCDM]+[.-]\\s+.+$");
    private static final Pattern NUMBERED_SECTION_PATTERN = Pattern.compile("^\\d+(?:\\.\\d+){1,4}\\.?\\s+.+$");
    private static final Pattern DECIMAL_PREFIX_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+){1,4})\\.?");
    private static final Pattern SINGLE_NUMBERED_SECTION_PATTERN = Pattern.compile("^\\d{1,2}\\.\\s+.+$");
    private static final Pattern ALPHA_SECTION_PATTERN = Pattern.compile("(?iu)^[a-z]\\)\\s+.+$");
    private static final Pattern ALPHA_DOT_SECTION_PATTERN = Pattern.compile("(?iu)^[a-z]\\.\\s+.+$");
    private static final Pattern BULLET_PATTERN = Pattern.compile("^[-+*]\\s+.+$");
    private static final Pattern FOOTNOTE_PATTERN = Pattern.compile("^\\d{1,3}\\s+\\S.+$");
    private static final Pattern TOC_LINE_PATTERN = Pattern.compile("(?iu).+\\s+\\d{1,3}$");
    private static final String PAGE_BREAK_SENTINEL = "[[PDF_PAGE_BREAK]]";

    public String toMarkdown(String rawText, String title) {
        List<String> lines = normalizeLines(rawText);
        lines = removeRepeatedPageNoise(lines);
        lines = removeTableOfContents(lines);
        lines = removeTrailingReferences(lines);
        lines = removeTrailingRepeatedOutline(lines);

        List<String> markdownBlocks = new ArrayList<>();
        if (title != null && !title.isBlank()) {
            markdownBlocks.add("# " + title.trim());
        }

        StringBuilder paragraph = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                flushParagraph(markdownBlocks, paragraph);
                continue;
            }
            if (isPageMarker(line)) {
                flushParagraph(markdownBlocks, paragraph);
                markdownBlocks.add(line);
                continue;
            }

            Heading heading = detectHeading(line, nextNonBlank(lines, i + 1));
            if (heading != null) {
                flushParagraph(markdownBlocks, paragraph);
                markdownBlocks.add(heading.markdown());
                int headingEnd = i;
                if (heading.consumesNext()) {
                    headingEnd = nextNonBlankIndex(lines, i + 1);
                    i = headingEnd;
                }
                i += consumeHeadingContinuation(lines, headingEnd + 1, markdownBlocks);
                continue;
            }

            if (isListLine(line) || isFootnote(line)) {
                flushParagraph(markdownBlocks, paragraph);
                markdownBlocks.add(line);
                continue;
            }

            if (paragraph.isEmpty()) {
                paragraph.append(line);
            } else if (shouldJoinParagraph(paragraph.toString(), line)) {
                paragraph.append(' ').append(line);
            } else {
                flushParagraph(markdownBlocks, paragraph);
                paragraph.append(line);
            }
        }
        flushParagraph(markdownBlocks, paragraph);

        return cleanupMarkdown(String.join("\n\n", markdownBlocks));
    }

    private List<String> normalizeLines(String rawText) {
        String normalized = rawText == null ? "" : rawText;
        normalized = normalized.replace("\r\n", "\n").replace('\r', '\n').replace("\f", "\n" + PAGE_BREAK_SENTINEL + "\n");
        String[] split = normalized.split("\\n", -1);
        List<String> lines = new ArrayList<>();
        boolean previousBlank = true;
        int page = 1;
        for (String value : split) {
            String line = normalizeWhitespace(value);
            if (PAGE_BREAK_SENTINEL.equals(line)) {
                page++;
                lines.add("<!-- page: " + page + " -->");
                previousBlank = false;
                continue;
            }
            if (PAGE_NUMBER_PATTERN.matcher(line).matches()) {
                continue;
            }
            if (line.isBlank()) {
                if (!previousBlank) {
                    lines.add("");
                }
                previousBlank = true;
                continue;
            }
            lines.add(line);
            previousBlank = false;
        }
        return lines;
    }

    private List<String> removeRepeatedPageNoise(List<String> lines) {
        Map<String, Integer> counts = new HashMap<>();
        for (String line : lines) {
            if (line.isBlank() || (isLikelyContentHeading(line) && !isRunningHeaderCandidate(line))) {
                continue;
            }
            String key = noiseKey(line);
            if (line.length() <= 90) {
                counts.merge(key, 1, Integer::sum);
            }
        }

        Set<String> noise = new HashSet<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() >= 4) {
                noise.add(entry.getKey());
            }
        }

        if (noise.isEmpty()) {
            return lines;
        }

        List<String> output = new ArrayList<>();
        for (String line : lines) {
            if (!line.isBlank()
                    && noise.contains(noiseKey(line))
                    && (!isLikelyContentHeading(line) || isRunningHeaderCandidate(line))) {
                continue;
            }
            output.add(line);
        }
        return output;
    }

    private List<String> removeTableOfContents(List<String> lines) {
        List<String> output = new ArrayList<>();
        boolean skipping = false;
        int tocLineCount = 0;
        for (String line : lines) {
            if (isTocTitle(line)) {
                skipping = true;
                tocLineCount = 0;
                continue;
            }
            if (skipping) {
                if (line.isBlank()) {
                    continue;
                }
                if (TOC_LINE_PATTERN.matcher(line).matches() || isLikelyContentHeading(line)) {
                    tocLineCount++;
                    continue;
                }
                if (tocLineCount >= 3) {
                    skipping = false;
                }
            }
            output.add(line);
        }
        return output;
    }

    private List<String> removeTrailingRepeatedOutline(List<String> lines) {
        Set<String> seenHeadings = new HashSet<>();
        int earliestOutlineStart = -1;
        int threshold = (int) Math.floor(lines.size() * 0.60);

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!isLikelyContentHeading(line)) {
                continue;
            }

            String key = headingKey(line);
            if (i >= threshold && seenHeadings.contains(key) && hasDenseHeadingWindow(lines, i)) {
                earliestOutlineStart = i;
                break;
            }
            seenHeadings.add(key);
        }

        if (earliestOutlineStart < 0) {
            return lines;
        }
        earliestOutlineStart = includeOutlineLeadIn(lines, earliestOutlineStart);
        return new ArrayList<>(lines.subList(0, earliestOutlineStart));
    }

    private List<String> removeTrailingReferences(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (isReferencesTitle(lines.get(i))) {
                return new ArrayList<>(lines.subList(0, i));
            }
        }
        return lines;
    }

    private Heading detectHeading(String line, String nextLine) {
        Matcher partMatcher = PART_PATTERN.matcher(stripAccents(line));
        if (partMatcher.matches()) {
            return new Heading("## " + normalizeHeadingText(line), false);
        }

        Matcher chapterMatcher = CHAPTER_PATTERN.matcher(stripAccents(line));
        if (chapterMatcher.matches()) {
            String headingText = normalizeHeadingText(line);
            if (isUppercaseTitle(nextLine) && !noiseKey(nextLine).equals(noiseKey(line))) {
                headingText = headingText + ": " + normalizeHeadingText(nextLine);
                return new Heading("### " + headingText, true);
            }
            return new Heading("### " + headingText, false);
        }

        if (ROMAN_SECTION_PATTERN.matcher(stripAccents(line)).matches() && isValidRomanHeading(line)) {
            return new Heading("#### " + normalizeHeadingText(line), false);
        }

        if (NUMBERED_SECTION_PATTERN.matcher(line).matches() && isValidNumberedHeading(line)) {
            int depth = numberedDepth(line);
            int level = Math.min(6, 2 + depth);
            return new Heading("#".repeat(level) + " " + normalizeHeadingText(line), false);
        }

        if (SINGLE_NUMBERED_SECTION_PATTERN.matcher(line).matches() && isValidSingleNumberedHeading(line)) {
            return new Heading("##### " + normalizeHeadingText(line), false);
        }

        if (isValidAlphaHeading(line)) {
            return new Heading("###### " + normalizeHeadingText(line), false);
        }

        return null;
    }

    private boolean shouldJoinParagraph(String current, String next) {
        if (next.isBlank() || isListLine(next) || detectHeading(next, null) != null) {
            return false;
        }
        char last = current.charAt(current.length() - 1);
        if (last == '-' || last == ':' || last == ';' || last == ',' || Character.isLowerCase(last)) {
            return true;
        }
        return Character.isLowerCase(next.charAt(0));
    }

    private boolean isListLine(String line) {
        return BULLET_PATTERN.matcher(line).matches();
    }

    private boolean isFootnote(String line) {
        return FOOTNOTE_PATTERN.matcher(line).matches()
                && (line.contains("Nxb") || line.contains("Toan tap") || line.contains("V.I.") || line.contains("C.Mac"));
    }

    private boolean isPageMarker(String line) {
        return line != null && line.matches("^<!--\\s*page:\\s*\\d+\\s*-->$");
    }

    private boolean isTocTitle(String line) {
        String stripped = stripAccents(line);
        return stripped.equalsIgnoreCase("muc luc") || stripped.equalsIgnoreCase("noi dung");
    }

    private boolean isReferencesTitle(String line) {
        String stripped = stripAccents(line).toLowerCase(Locale.ROOT);
        return stripped.equals("tai lieu tham khao")
                || stripped.startsWith("tai lieu chu yeu su dung")
                || stripped.startsWith("danh muc tai lieu tham khao");
    }

    private boolean isLikelyContentHeading(String line) {
        String stripped = stripAccents(line);
        return PART_PATTERN.matcher(stripped).matches()
                || CHAPTER_PATTERN.matcher(stripped).matches()
                || (ROMAN_SECTION_PATTERN.matcher(stripped).matches() && isValidRomanHeading(line))
                || (NUMBERED_SECTION_PATTERN.matcher(line).matches() && isValidNumberedHeading(line))
                || (SINGLE_NUMBERED_SECTION_PATTERN.matcher(line).matches() && isValidSingleNumberedHeading(line))
                || isValidAlphaHeading(line);
    }

    private int consumeHeadingContinuation(List<String> lines, int start, List<String> markdownBlocks) {
        if (markdownBlocks.isEmpty()) {
            return 0;
        }
        String currentHeading = markdownBlocks.getLast();
        boolean standaloneTitleHeading = isStandaloneTitleHeading(currentHeading);
        if (!isIncompleteHeading(currentHeading) && !standaloneTitleHeading) {
            return 0;
        }

        int consumedLines = 0;
        int appendedLines = 0;
        int blankRun = 0;
        StringBuilder merged = new StringBuilder(currentHeading);
        for (int i = start; i < lines.size() && appendedLines < 3; i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                blankRun++;
                if (blankRun > 2) {
                    break;
                }
                consumedLines++;
                continue;
            }
            blankRun = 0;
            if (detectHeading(line, nextNonBlank(lines, i + 1)) != null || isListLine(line) || isFootnote(line)) {
                break;
            }
            if (!isHeadingContinuationCandidate(line, merged.toString(), standaloneTitleHeading)) {
                break;
            }

            consumedLines++;
            if (!isDuplicateHeadingFragment(merged.toString(), line)) {
                merged.append(' ').append(normalizeHeadingText(line));
                appendedLines++;
            }
            if (!standaloneTitleHeading && !isIncompleteHeading(merged.toString())) {
                break;
            }
        }

        if (appendedLines > 0) {
            markdownBlocks.set(markdownBlocks.size() - 1, merged.toString());
        }
        return consumedLines;
    }

    private boolean isIncompleteHeading(String heading) {
        String normalized = heading == null ? "" : heading.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith(",")
                || normalized.endsWith(" và")
                || normalized.endsWith(" của")
                || normalized.endsWith(" lên")
                || normalized.endsWith(" với")
                || normalized.endsWith(" trong")
                || normalized.endsWith(" để")
                || normalized.endsWith(" thực")
                || normalized.endsWith(" đế")
                || normalized.endsWith(" hội")
                || normalized.endsWith(" ý")
                || normalized.endsWith(" tôn")
                || normalized.endsWith(" cơ")
                || normalized.endsWith(" nhân")
                || normalized.endsWith(" lãnh")
                || normalized.endsWith(" cộng")
                || normalized.endsWith(" chính");
    }

    private boolean isHeadingContinuationCandidate(String line, String currentHeading, boolean standaloneTitleHeading) {
        if (line == null || line.isBlank() || line.length() > 180) {
            return false;
        }
        if (standaloneTitleHeading && line.matches("^\\(.+\\)$")) {
            return true;
        }
        if (standaloneTitleHeading && isStandaloneTitleLine(line)) {
            return true;
        }
        if (!isIncompleteHeading(currentHeading)) {
            return false;
        }
        if (line.matches("^[a-z].*") && !isUppercaseTitle(line)) {
            return true;
        }
        return isUppercaseTitle(line) || Character.isUpperCase(line.charAt(0));
    }

    private boolean isStandaloneTitleHeading(String heading) {
        if (heading == null || heading.contains(":")) {
            return false;
        }
        String stripped = stripAccents(heading);
        return stripped.matches("(?iu)^##\\s+phan\\s+[ivxlcdm]+\\b.*$")
                || stripped.matches("(?iu)^###\\s+chuong\\s+[0-9ivxlcdm]+\\b\\s*$");
    }

    private boolean isStandaloneTitleLine(String line) {
        if (line == null || line.isBlank() || line.length() > 120) {
            return false;
        }
        if (line.matches(".*[.!?…]$")) {
            return false;
        }
        return isUppercaseTitle(line) || Character.isUpperCase(line.charAt(0));
    }

    private boolean isDuplicateHeadingFragment(String heading, String candidate) {
        String headingKey = stripMarkdownPrefix(noiseKey(heading));
        String candidateKey = noiseKey(candidate);
        if (candidateKey.length() < 12) {
            return false;
        }
        return headingKey.contains(candidateKey)
                || candidateKey.startsWith(firstWords(headingKey, 5));
    }

    private String stripMarkdownPrefix(String value) {
        return value == null ? "" : value.replaceFirst("^#+\\s+", "");
    }

    private String firstWords(String value, int maxWords) {
        String[] words = value == null ? new String[0] : value.trim().split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < words.length && i < maxWords; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(words[i]);
        }
        return builder.toString();
    }

    private boolean isValidRomanHeading(String line) {
        Matcher matcher = Pattern.compile("^([IVXLCDM]+)[.-]\\s+.+$").matcher(stripAccents(line));
        if (!matcher.matches()) {
            return false;
        }
        int value = romanToInt(matcher.group(1));
        return value > 0 && value <= 15;
    }

    private boolean isValidNumberedHeading(String line) {
        Matcher matcher = DECIMAL_PREFIX_PATTERN.matcher(line);
        if (!matcher.find()) {
            return false;
        }
        String[] segments = matcher.group(1).split("\\.");
        if (segments.length == 2 && segments[1].length() == 3) {
            return false;
        }
        for (String segment : segments) {
            if (segment.length() > 2) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidSingleNumberedHeading(String line) {
        if (line == null || line.length() > 160 || line.endsWith("?")) {
            return false;
        }
        String rest = line.replaceFirst("^\\d{1,2}\\.\\s+", "");
        if (rest.isBlank() || rest.matches("^\\d+.*")) {
            return false;
        }
        if (looksLikeCitationOrReference(rest) || looksLikeReviewQuestion(rest)) {
            return false;
        }
        if (rest.endsWith(";") || rest.endsWith("...”") || rest.endsWith("...")) {
            return false;
        }
        if (isFootnote(line)) {
            return false;
        }
        return Character.isUpperCase(rest.charAt(0)) || isUppercaseTitle(rest);
    }

    private boolean looksLikeCitationOrReference(String value) {
        String stripped = stripAccents(value);
        String lower = stripped.toLowerCase(Locale.ROOT);
        return stripped.contains("Nxb")
                || lower.contains("toan tap")
                || lower.contains("van kien")
                || lower.contains("v.i.lenin")
                || lower.contains("c.mac")
                || lower.contains("ph.angghen")
                || stripped.matches("(?iu).+\\btr\\.\\s*\\d+.*")
                || stripped.matches("(?iu)^(Dang Cong san Viet Nam|Ho Chi Minh|Hoc vien|Vien Nghien cuu|Vien Lich su|Bo Ngoai giao|Ban Chi dao|Robert).*");
    }

    private boolean looksLikeReviewQuestion(String value) {
        String stripped = stripAccents(value).toLowerCase(Locale.ROOT);
        return stripped.startsWith("trinh bay ")
                || stripped.startsWith("phan tich ")
                || stripped.startsWith("neu ")
                || stripped.startsWith("vi sao ")
                || stripped.startsWith("tai sao ")
                || stripped.startsWith("dac trung ")
                || stripped.startsWith("van de co ban ")
                || stripped.startsWith("su doi lap ")
                || stripped.startsWith("vai tro ")
                || stripped.startsWith("noi dung co ban va y nghia ")
                || stripped.startsWith("khai quat qua trinh ");
    }

    private boolean isValidAlphaHeading(String line) {
        String stripped = stripAccents(line);
        boolean matches = ALPHA_SECTION_PATTERN.matcher(stripped).matches()
                || ALPHA_DOT_SECTION_PATTERN.matcher(stripped).matches();
        if (!matches || line.length() > 120) {
            return false;
        }
        String rest = stripped.replaceFirst("(?iu)^[a-z][).]\\s+", "");
        if (Pattern.compile("(?iu)\\b[a-z][).]\\s+").matcher(rest).find()) {
            return false;
        }
        return !line.endsWith(",") && !line.contains("v.v");
    }

    private int romanToInt(String roman) {
        int total = 0;
        int previous = 0;
        for (int i = roman.length() - 1; i >= 0; i--) {
            int value = switch (roman.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                case 'D' -> 500;
                case 'M' -> 1000;
                default -> 0;
            };
            if (value < previous) {
                total -= value;
            } else {
                total += value;
            }
            previous = value;
        }
        return total;
    }

    private boolean hasDenseHeadingWindow(List<String> lines, int start) {
        int headings = 0;
        int nonBlank = 0;
        int end = Math.min(lines.size(), start + 30);
        for (int i = start; i < end; i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            nonBlank++;
            if (isLikelyContentHeading(line) || line.length() <= 80) {
                headings++;
            }
        }
        return nonBlank >= 6 && headings >= Math.max(5, nonBlank * 2 / 3);
    }

    private int includeOutlineLeadIn(List<String> lines, int start) {
        int adjusted = start;
        int inspected = 0;
        for (int i = start - 1; i >= 0 && inspected < 4; i--) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            inspected++;
            if (line.length() <= 140 && !line.matches(".*[.!?…]$")) {
                adjusted = i;
                continue;
            }
            break;
        }
        return adjusted;
    }

    private String headingKey(String line) {
        String stripped = noiseKey(line).replaceAll("\\s+", " ").trim();
        Matcher chapterMatcher = Pattern.compile("(?iu)^chuong\\s+([0-9ivxlcdm]+|nhap\\s+mon)\\b.*$").matcher(stripped);
        if (chapterMatcher.matches()) {
            return "chuong " + chapterMatcher.group(1).toLowerCase(Locale.ROOT);
        }
        Matcher partMatcher = Pattern.compile("(?iu)^phan\\s+([ivxlcdm]+)\\b.*$").matcher(stripped);
        if (partMatcher.matches()) {
            return "phan " + partMatcher.group(1).toLowerCase(Locale.ROOT);
        }
        return stripped;
    }

    private boolean isRunningHeaderCandidate(String line) {
        String stripped = stripAccents(line);
        return stripped.matches("(?iu)^chuong\\s+[0-9ivxlcdm]+:\\s+.+$")
                && !isUppercaseTitle(line);
    }

    private boolean isUppercaseTitle(String line) {
        if (line == null || line.isBlank() || line.length() > 180) {
            return false;
        }
        int letters = 0;
        int uppercase = 0;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (Character.isLetter(ch)) {
                letters++;
                if (Character.isUpperCase(ch)) {
                    uppercase++;
                }
            }
        }
        return letters >= 8 && uppercase >= letters * 0.75;
    }

    private String nextNonBlank(List<String> lines, int start) {
        int index = nextNonBlankIndex(lines, start);
        return index >= 0 ? lines.get(index) : null;
    }

    private int nextNonBlankIndex(List<String> lines, int start) {
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.isBlank()) {
                return i;
            }
        }
        return -1;
    }

    private int numberedDepth(String line) {
        Matcher matcher = DECIMAL_PREFIX_PATTERN.matcher(line);
        if (!matcher.find()) {
            return 1;
        }
        return matcher.group(1).split("\\.").length;
    }

    private void flushParagraph(List<String> markdownBlocks, StringBuilder paragraph) {
        if (paragraph.isEmpty()) {
            return;
        }
        markdownBlocks.add(paragraph.toString().trim());
        paragraph.setLength(0);
    }

    private String normalizeWhitespace(String value) {
        return value == null ? "" : value.trim().replaceAll("[\\t ]+", " ");
    }

    private String normalizeHeadingText(String line) {
        String normalized = normalizeWhitespace(line);
        if (normalized.isBlank()) {
            return normalized;
        }
        if (normalized.equals(normalized.toUpperCase(Locale.ROOT)) && normalized.length() > 8) {
            return titleCaseVietnamese(normalized);
        }
        return normalized;
    }

    private String titleCaseVietnamese(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder(lower.length());
        boolean uppercaseNext = true;
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (Character.isLetter(ch) && uppercaseNext) {
                builder.append(Character.toTitleCase(ch));
                uppercaseNext = false;
            } else {
                builder.append(ch);
                uppercaseNext = Character.isWhitespace(ch) || ch == '-' || ch == ':';
            }
        }
        return builder.toString();
    }

    private String cleanupMarkdown(String markdown) {
        return markdown
                .replaceAll("(?m)^\\s+$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String noiseKey(String line) {
        return stripAccents(line).toLowerCase(Locale.ROOT);
    }

    private String stripAccents(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.replace('đ', 'd').replace('Đ', 'D');
    }

    private record Heading(String markdown, boolean consumesNext) {
    }
}
