package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class SourceAttributionFormatter {

    private static final int MAX_SOURCE_LABELS = 3;

    public List<String> formatSources(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        Map<String, String> labelsByKey = new LinkedHashMap<>();
        for (DocumentChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            labelsByKey.putIfAbsent(dedupeKey(chunk), format(chunk));
            if (labelsByKey.size() >= MAX_SOURCE_LABELS) {
                break;
            }
        }
        return new ArrayList<>(labelsByKey.values());
    }

    public String format(DocumentChunk chunk) {
        if (chunk == null) {
            return "Nguồn tài liệu";
        }

        String title = documentTitle(chunk);
        String pageRange = pageRange(chunk);

        StringBuilder label = new StringBuilder(title);
        if (!pageRange.isBlank()) {
            label.append(" (").append(pageRange).append(")");
        }
        return label.toString();
    }

    public String pageRange(DocumentChunk chunk) {
        if (chunk == null) {
            return "";
        }
        Integer pageFrom = chunk.getPageFrom();
        Integer pageTo = chunk.getPageTo();
        if (pageFrom == null && pageTo == null) {
            return "";
        }
        int from = pageFrom != null ? pageFrom : pageTo;
        int to = pageTo != null ? pageTo : pageFrom;
        if (from == to) {
            return "trang " + from;
        }
        int start = Math.min(from, to);
        int end = Math.max(from, to);
        return "trang " + start + "-" + end;
    }

    private String dedupeKey(DocumentChunk chunk) {
        Document document = chunk.getDocument();
        String documentKey = document == null || document.getId() == null
                ? normalize(documentTitle(chunk))
                : String.valueOf(document.getId());
        return documentKey + "|" + pageKey(chunk);
    }

    private String pageKey(DocumentChunk chunk) {
        Integer pageFrom = chunk.getPageFrom();
        Integer pageTo = chunk.getPageTo();
        if (pageFrom == null && pageTo == null) {
            return "";
        }
        int from = pageFrom != null ? pageFrom : pageTo;
        int to = pageTo != null ? pageTo : pageFrom;
        return Math.min(from, to) + "-" + Math.max(from, to);
    }

    private String documentTitle(DocumentChunk chunk) {
        Document document = chunk.getDocument();
        if (document == null || document.getTitle() == null || document.getTitle().isBlank()) {
            return "Nguồn tài liệu";
        }
        return document.getTitle().trim();
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
