package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RagScopeResolverService {

    private static final Set<String> ALL_SCOPE_NODE_TYPES = Set.of(
            "document", "part", "chapter", "section", "subsection", "subsection_level2"
    );
    private static final Set<String> PART_TYPES = Set.of("part");
    private static final Set<String> CHAPTER_TYPES = Set.of("chapter");
    private static final Set<String> SECTION_TYPES = Set.of("section", "subsection", "subsection_level2");
    private static final Set<String> SUBSECTION_TYPES = Set.of("subsection", "subsection_level2");
    private static final Set<String> DOCUMENT_TYPES = Set.of("document");
    private static final Pattern EXPLICIT_SCOPE_PATTERN = Pattern.compile("""
            \\b(tieu\\s+muc|subsection|phan|part|chuong|chapter|muc|section)\\s+([ivxlcdm]+|\\d+(?:\\.\\d+)*)
            """, Pattern.CASE_INSENSITIVE | Pattern.COMMENTS);
    private static final int MIN_LEXICAL_SCORE = 10;
    private static final int MIN_LEXICAL_MARGIN = 5;

    private final DocumentNodeRepository documentNodeRepository;
    private final LlmScopeDisambiguationService llmScopeDisambiguationService;

    public RagScopeResolverService(DocumentNodeRepository documentNodeRepository) {
        this(documentNodeRepository, null);
    }

    @Autowired
    public RagScopeResolverService(DocumentNodeRepository documentNodeRepository,
                                   LlmScopeDisambiguationService llmScopeDisambiguationService) {
        this.documentNodeRepository = documentNodeRepository;
        this.llmScopeDisambiguationService = llmScopeDisambiguationService;
    }

    public Optional<DocumentNode> resolve(ChatSession session, String question) {
        return resolveDetailed(session, question).resolvedNode();
    }

    public boolean hasExplicitScopeHint(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        return EXPLICIT_SCOPE_PATTERN.matcher(normalize(question).trim()).find();
    }

    public ScopeResolution resolveDetailed(ChatSession session, String question) {
        ScopeResolution deterministic = resolveDeterministicOnly(session, question);
        if (deterministic.status() == ScopeResolution.Status.RESOLVED) {
            return deterministic;
        }
        if (deterministic.status() == ScopeResolution.Status.AMBIGUOUS
                && llmScopeDisambiguationService != null
                && !deterministic.candidates().isEmpty()) {
            Optional<ScopeResolution> llmResolution = llmScopeDisambiguationService.resolve(question, deterministic.candidates());
            if (llmResolution.isPresent()) {
                return llmResolution.get();
            }
        }
        return deterministic;
    }

    public ScopeResolution resolveDeterministicOnly(ChatSession session, String question) {
        if (session == null || session.getSubject() == null || session.getSubject().getId() == null) {
            return ScopeResolution.notFound("missing_subject");
        }

        String normalizedQuestion = normalize(question);
        ScopeRequest scopeRequest = scopeRequest(normalizedQuestion);
        List<DocumentNode> candidates = documentNodeRepository.findBySubjectIdAndNodeTypeInOrderByOrderIndexAsc(
                session.getSubject().getId(),
                new ArrayList<>(scopeRequest.nodeTypes())
        ).stream()
                .filter(node -> scopeRequest.nodeTypes().contains(node.getNodeType()))
                .toList();
        if (candidates.isEmpty()) {
            return ScopeResolution.notFound("no_scope_candidates");
        }

        return resolveDeterministic(normalizedQuestion, scopeRequest, candidates);
    }

    private ScopeResolution resolveDeterministic(String normalizedQuestion,
                                                 ScopeRequest scopeRequest,
                                                 List<DocumentNode> candidates) {
        if (scopeRequest.wholeDocument()) {
            return candidates.stream()
                    .min(Comparator.comparing(this::safeOrder))
                    .map(node -> ScopeResolution.resolved(node, 0.95, "whole_document_keyword", candidates))
                    .orElseGet(() -> ScopeResolution.notFound("document_root_not_found"));
        }

        ScopeHint hint = scopeRequest.hint();
        if (hint != null) {
            List<DocumentNode> exactNumberMatches = candidates.stream()
                    .filter(node -> matchesNumber(node, hint))
                    .sorted(Comparator.comparing(this::safeOrder))
                    .toList();
            if (exactNumberMatches.size() == 1) {
                return ScopeResolution.resolved(exactNumberMatches.getFirst(), 0.96, "explicit_scope_number", exactNumberMatches);
            }
            if (exactNumberMatches.size() > 1) {
                return ScopeResolution.ambiguous("multiple_explicit_scope_number_matches", exactNumberMatches);
            }
            return ScopeResolution.notFound("explicit_scope_not_found");
        }

        List<ScoredNode> scored = candidates.stream()
                .map(node -> new ScoredNode(node, lexicalScore(normalizedQuestion, node)))
                .filter(scoredNode -> scoredNode.score() > 0)
                .sorted(Comparator.comparingInt(ScoredNode::score).reversed()
                        .thenComparing(scoredNode -> safeOrder(scoredNode.node())))
                .toList();
        if (scored.isEmpty()) {
            return ScopeResolution.notFound("no_lexical_match");
        }

        ScoredNode top = scored.getFirst();
        int secondScore = scored.size() > 1 ? scored.get(1).score() : 0;
        List<DocumentNode> topCandidates = scored.stream()
                .limit(10)
                .map(ScoredNode::node)
                .toList();
        if (top.score() >= MIN_LEXICAL_SCORE && top.score() - secondScore >= MIN_LEXICAL_MARGIN) {
            double confidence = Math.min(0.94, 0.60 + (top.score() / 100.0));
            return ScopeResolution.resolved(top.node(), confidence, "lexical_match", topCandidates);
        }
        return ScopeResolution.ambiguous("lexical_match_not_confident", topCandidates);
    }

    private ScopeRequest scopeRequest(String normalizedQuestion) {
        if (isWholeDocumentRequest(normalizedQuestion)) {
            return new ScopeRequest(DOCUMENT_TYPES, null, true);
        }

        ScopeHint hint = extractScopeHint(normalizedQuestion);
        if (hint != null) {
            return new ScopeRequest(typesForKeyword(hint.keyword()), hint, false);
        }
        return new ScopeRequest(ALL_SCOPE_NODE_TYPES, null, false);
    }

    private boolean isWholeDocumentRequest(String normalizedQuestion) {
        return containsPhrase(normalizedQuestion, "tom tat mon hoc")
                || containsPhrase(normalizedQuestion, "tom tat toan bo")
                || containsPhrase(normalizedQuestion, "tom tat tai lieu")
                || containsPhrase(normalizedQuestion, "tom tat ca tai lieu")
                || containsPhrase(normalizedQuestion, "tom tat giao trinh")
                || containsPhrase(normalizedQuestion, "cau hoi on tap toan bo")
                || containsPhrase(normalizedQuestion, "cau hoi on tap mon hoc")
                || containsPhrase(normalizedQuestion, "cau hoi on tap tai lieu")
                || containsPhrase(normalizedQuestion, "cau hoi on tap giao trinh")
                || containsPhrase(normalizedQuestion, "bo cau hoi toan bo mon hoc")
                || containsPhrase(normalizedQuestion, "bo cau hoi toan bo tai lieu")
                || containsPhrase(normalizedQuestion, "bo cau hoi toan bo giao trinh")
                || containsPhrase(normalizedQuestion, "de cuong on tap toan bo")
                || containsPhrase(normalizedQuestion, "de cuong on tap mon hoc")
                || containsPhrase(normalizedQuestion, "de cuong on tap tai lieu")
                || containsPhrase(normalizedQuestion, "de cuong on tap giao trinh")
                || containsPhrase(normalizedQuestion, "de cuong mon hoc");
    }

    private ScopeHint extractScopeHint(String normalizedQuestion) {
        Matcher matcher = EXPLICIT_SCOPE_PATTERN.matcher(normalizedQuestion);
        if (!matcher.find()) {
            return null;
        }
        String keyword = matcher.group(1).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String rawNumber = matcher.group(2).toLowerCase(Locale.ROOT);
        return new ScopeHint(keyword, rawNumber, normalizeNumber(rawNumber));
    }

    private Set<String> typesForKeyword(String keyword) {
        return switch (keyword) {
            case "phan", "part" -> PART_TYPES;
            case "chuong", "chapter" -> CHAPTER_TYPES;
            case "subsection", "tieu muc" -> SUBSECTION_TYPES;
            case "muc", "section" -> SECTION_TYPES;
            default -> ALL_SCOPE_NODE_TYPES;
        };
    }

    private boolean matchesNumber(DocumentNode node, ScopeHint hint) {
        String title = normalize(valueOrEmpty(node.getTitle()));
        String path = normalize(valueOrEmpty(node.getSectionPath()));
        return matchesNumberInTitle(title, hint) || matchesNumberInPath(path, hint);
    }

    private boolean matchesNumberInTitle(String text, ScopeHint hint) {
        for (String number : hint.numberVariants()) {
            if (containsPhrase(text, number)
                    || text.contains(" " + number + ".")
                    || matchesNumberWithScopeKeyword(text, hint.keyword(), number)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesNumberInPath(String text, ScopeHint hint) {
        for (String number : hint.numberVariants()) {
            if (matchesNumberWithScopeKeyword(text, hint.keyword(), number)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesNumberWithScopeKeyword(String text, String keyword, String number) {
        return switch (keyword) {
            case "chuong", "chapter" -> containsPhrase(text, "chuong " + number)
                    || containsPhrase(text, "chapter " + number);
            case "phan", "part" -> containsPhrase(text, "phan " + number)
                    || containsPhrase(text, "part " + number);
            case "muc", "section" -> containsPhrase(text, "muc " + number)
                    || containsPhrase(text, "section " + number);
            case "subsection", "tieu muc" -> containsPhrase(text, "subsection " + number)
                    || containsPhrase(text, "tieu muc " + number);
            default -> false;
        };
    }

    private int lexicalScore(String normalizedQuestion, DocumentNode node) {
        String title = normalize(valueOrEmpty(node.getTitle()));
        String path = normalize(valueOrEmpty(node.getSectionPath()));
        String queryPhrase = searchableQuestion(normalizedQuestion);
        int score = 0;

        if (!queryPhrase.isBlank()) {
            if (containsPhrase(title, queryPhrase)) {
                score += 100;
            } else if (containsPhrase(path, queryPhrase)) {
                score += 35;
            }
        }

        for (String token : tokens(queryPhrase)) {
            if (title.contains(" " + token + " ")) {
                score += 4;
            } else if (path.contains(" " + token + " ")) {
                score += 1;
            }
        }
        return score;
    }

    private String searchableQuestion(String normalizedQuestion) {
        String value = normalizedQuestion;
        for (String stopPhrase : List.of(
                "tom tat", "tao bo cau hoi", "bo cau hoi", "cau hoi on tap",
                "hay", "giup toi", "cho toi", "ve", "noi dung", "phan noi dung"
        )) {
            value = value.replace(" " + stopPhrase + " ", " ");
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private List<String> tokens(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : value.split("\\s+")) {
            if (token.length() < 3 && !token.matches("\\d+")) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    private String normalizeNumber(String rawNumber) {
        String value = rawNumber.toLowerCase(Locale.ROOT);
        if (value.matches("[ivxlcdm]+")) {
            int roman = romanToInt(value);
            return roman > 0 ? String.valueOf(roman) : value;
        }
        return value;
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

    private static String intToRoman(int value) {
        if (value <= 0 || value > 3999) {
            return "";
        }
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

    private int safeOrder(DocumentNode node) {
        return node.getOrderIndex() == null ? Integer.MAX_VALUE : node.getOrderIndex();
    }

    private boolean containsPhrase(String text, String phrase) {
        if (text == null || phrase == null || phrase.isBlank()) {
            return false;
        }
        return text.contains(" " + phrase.trim() + " ");
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
        return " " + normalized.replaceAll("[^\\p{L}\\p{N}\\s.]", " ")
                .replaceAll("\\s+", " ")
                .trim() + " ";
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private record ScopeRequest(Set<String> nodeTypes, ScopeHint hint, boolean wholeDocument) {
    }

    private record ScopeHint(String keyword, String rawNumber, String normalizedNumber) {
        private List<String> numberVariants() {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            values.add(rawNumber);
            values.add(normalizedNumber);
            if (normalizedNumber != null && normalizedNumber.matches("\\d+")) {
                int number = Integer.parseInt(normalizedNumber);
                if (number > 0 && number <= 3999) {
                    values.add(intToRoman(number));
                }
            }
            return values.stream().filter(value -> value != null && !value.isBlank()).toList();
        }
    }

    private record ScoredNode(DocumentNode node, int score) {
    }
}
