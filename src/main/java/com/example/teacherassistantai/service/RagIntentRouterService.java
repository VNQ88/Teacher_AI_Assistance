package com.example.teacherassistantai.service;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Locale;

@Service
public class RagIntentRouterService {

    public RagChatIntent route(String question) {
        String normalized = normalize(question);
        if (normalized.isBlank()) {
            return RagChatIntent.FACTUAL_QA;
        }
        if (containsAny(normalized,
                "cau hoi on tap",
                "tao cau hoi",
                "bo cau hoi",
                "trac nghiem",
                "dung sai",
                "dien khuyet",
                "quiz",
                "review question")) {
            return RagChatIntent.REVIEW_QUESTION_GENERATION;
        }
        if (containsAny(normalized,
                "tom tat",
                "khai quat",
                "y chinh",
                "noi dung chinh",
                "summary",
                "summarize")) {
            return RagChatIntent.SECTION_SUMMARY;
        }
        return RagChatIntent.FACTUAL_QA;
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd')
                .replace('Đ', 'D')
                .toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[^\\p{L}\\p{N}\\s.]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
