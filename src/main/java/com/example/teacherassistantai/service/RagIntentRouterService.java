package com.example.teacherassistantai.service;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class RagIntentRouterService {

    private static final List<String> REVIEW_QUESTION_GENERATION_PHRASES = List.of(
            "cau hoi on tap",
            "cau hoi luyen tap",
            "cau hoi tu luyen",
            "tao cau hoi",
            "tao bo cau hoi",
            "soan cau hoi",
            "lap cau hoi",
            "ra cau hoi",
            "sinh cau hoi",
            "bo cau hoi",
            "tao de",
            "soan de",
            "lap de",
            "ra de",
            "de on tap",
            "de kiem tra",
            "de trac nghiem",
            "de cuong on tap",
            "de cuong mon hoc",
            "bai tap on tap",
            "bai tap luyen tap",
            "kiem tra nhanh",
            "kiem tra kien thuc",
            "kiem tra thu",
            "practice question",
            "review question",
            "question set",
            "practice quiz",
            "quiz",
            "flashcard"
    );

    private static final List<String> REVIEW_QUESTION_TYPE_PHRASES = List.of(
            "trac nghiem",
            "dung sai",
            "dien khuyet",
            "multiple choice",
            "true false",
            "fill in the blank"
    );

    private static final List<String> REVIEW_QUESTION_ACTION_PHRASES = List.of(
            "tao",
            "soan",
            "lap",
            "ra",
            "sinh",
            "viet",
            "cho toi",
            "giup toi",
            "can",
            "muon"
    );

    private static final List<String> SECTION_SUMMARY_PHRASES = List.of(
            "tom tat",
            "tom luoc",
            "rut gon",
            "khai quat",
            "tong ket",
            "tong hop y chinh",
            "y chinh",
            "noi dung chinh",
            "diem chinh",
            "dai y",
            "noi dung cot loi",
            "noi dung trong tam",
            "chuong nay noi ve gi",
            "phan nay noi ve gi",
            "muc nay noi ve gi",
            "noi ve dieu gi",
            "summary",
            "summarize",
            "summarise",
            "overview",
            "brief"
    );

    private static final List<String> DOCUMENT_OUTLINE_PHRASES = List.of(
            "muc luc",
            "outline",
            "dan y",
            "cau truc",
            "cau truc tai lieu",
            "cau truc giao trinh",
            "tai lieu gom nhung phan nao",
            "tai lieu gom nhung chuong nao",
            "giao trinh gom nhung chuong nao",
            "mon hoc gom nhung chuong nao",
            "mon hoc gom nhung phan nao",
            "gom nhung phan nao",
            "gom nhung chuong nao",
            "gom cac phan nao",
            "gom cac chuong nao",
            "co nhung phan nao",
            "co nhung chuong nao",
            "danh sach chuong",
            "danh sach phan",
            "cac chuong trong tai lieu",
            "cac phan trong tai lieu"
    );

    private static final List<String> DEFINITION_KEYWORD_PHRASES = List.of(
            "tom tat",
            "tom luoc",
            "khai quat",
            "muc luc",
            "outline",
            "dan y",
            "cau truc",
            "trac nghiem",
            "dung sai",
            "dien khuyet",
            "quiz",
            "flashcard",
            "summary",
            "overview",
            "brief",
            "multiple choice",
            "true false",
            "fill in the blank"
    );

    private static final Pattern REVIEW_QUESTION_COUNT_PATTERN = Pattern.compile(
            "\\b(\\d+|mot|hai|ba|bon|nam|sau|bay|tam|chin|muoi|vai|nhieu)\\s+(cau|cau hoi|bai|bai tap)\\b"
    );

    public RagChatIntent route(String question) {
        String normalized = normalize(question);
        if (normalized.isBlank()) {
            return RagChatIntent.FACTUAL_QA;
        }
        if (isDefinitionQuestion(normalized)) {
            return RagChatIntent.FACTUAL_QA;
        }
        if (isReviewQuestionGeneration(normalized)) {
            return RagChatIntent.REVIEW_QUESTION_GENERATION;
        }
        if (isDocumentOutline(normalized)) {
            return RagChatIntent.DOCUMENT_OUTLINE;
        }
        if (isSectionSummary(normalized)) {
            return RagChatIntent.SECTION_SUMMARY;
        }
        return RagChatIntent.FACTUAL_QA;
    }

    private boolean isReviewQuestionGeneration(String text) {
        if (containsAny(text, REVIEW_QUESTION_GENERATION_PHRASES)) {
            return true;
        }
        return containsAny(text, REVIEW_QUESTION_TYPE_PHRASES)
                && (containsAny(text, REVIEW_QUESTION_ACTION_PHRASES)
                || REVIEW_QUESTION_COUNT_PATTERN.matcher(text).find());
    }

    private boolean isSectionSummary(String text) {
        return containsAny(text, SECTION_SUMMARY_PHRASES);
    }

    private boolean isDocumentOutline(String text) {
        return containsAny(text, DOCUMENT_OUTLINE_PHRASES);
    }

    private boolean isDefinitionQuestion(String text) {
        for (String keyword : DEFINITION_KEYWORD_PHRASES) {
            if (containsPhrase(text, keyword + " la gi")
                    || containsPhrase(text, keyword + " nghia la gi")
                    || containsPhrase(text, keyword + " co nghia la gi")) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String text, List<String> needles) {
        for (String needle : needles) {
            if (containsPhrase(text, needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsPhrase(String text, String needle) {
        return (" " + text + " ").contains(" " + needle + " ");
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
        return normalized.replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
