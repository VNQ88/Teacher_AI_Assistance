package com.example.teacherassistantai.service;

import com.example.teacherassistantai.exception.InvalidDataException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class MarkdownChunkingService {

    private static final int TARGET_SIZE = 800;
    private static final int MAX_CHUNK_SIZE = 1500;
    private static final int OVERLAP_SIZE = 200;
    private static final int MIN_CHUNK_SIZE = 500;
    private static final int TABLE_MAX_SIZE = 3200;
    private static final int FILE_SIZE_GATE_BYTES = 100 * 1024;

    private static final Pattern HEADER_PATTERN = Pattern.compile("^(#{1,3})\\s+.+$");
    private static final Pattern TABLE_LINE_PATTERN = Pattern.compile("^\\s*\\|.*\\|\\s*$");

    public List<String> chunk(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            throw new InvalidDataException("Markdown content is empty");
        }

        List<String> normalizedInputs = preSplitLargeInput(markdown);
        List<String> chunks = new ArrayList<>();
        for (String input : normalizedInputs) {
            chunks.addAll(chunkStructured(input));
        }
        return chunks;
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
}
