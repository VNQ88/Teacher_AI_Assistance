package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RagScopeResolverService {

    private static final Set<String> ENRICHABLE_NODE_TYPES = Set.of("part", "chapter", "section", "subsection");
    private static final Pattern EXPLICIT_SCOPE_PATTERN = Pattern.compile("""
            \\b(phan|part|chuong|chapter|muc|section|subsection)\\s+([ivxlcdm]+|\\d+(?:\\.\\d+)*)
            """, Pattern.CASE_INSENSITIVE | Pattern.COMMENTS);
    private static final Map<String, String> NODE_TYPE_BY_KEYWORD = Map.of(
            "phan", "part",
            "part", "part",
            "chuong", "chapter",
            "chapter", "chapter",
            "muc", "section",
            "section", "section",
            "subsection", "subsection"
    );

    private final DocumentNodeRepository documentNodeRepository;

    public Optional<DocumentNode> resolve(ChatSession session, String question) {
        if (session == null || session.getSubject() == null || session.getSubject().getId() == null) {
            return Optional.empty();
        }

        String normalizedQuestion = normalize(question);
        List<DocumentNode> candidates = documentNodeRepository.findBySubjectIdAndNodeTypeInOrderByOrderIndexAsc(
                session.getSubject().getId(),
                ENRICHABLE_NODE_TYPES.stream().toList()
        );
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        ScopeHint hint = extractScopeHint(normalizedQuestion);
        if (hint != null) {
            Optional<DocumentNode> exact = candidates.stream()
                    .filter(node -> hint.nodeType().equals(node.getNodeType()))
                    .filter(node -> matchesNumber(node, hint.number()))
                    .min(Comparator.comparing(DocumentNode::getOrderIndex));
            if (exact.isPresent()) {
                return exact;
            }
        }

        return candidates.stream()
                .map(node -> new ScoredNode(node, lexicalScore(normalizedQuestion, node)))
                .filter(scored -> scored.score() > 0)
                .max(Comparator.comparingInt(ScoredNode::score)
                        .thenComparing(scored -> -safeOrder(scored.node())))
                .map(ScoredNode::node);
    }

    private ScopeHint extractScopeHint(String normalizedQuestion) {
        Matcher matcher = EXPLICIT_SCOPE_PATTERN.matcher(normalizedQuestion);
        if (!matcher.find()) {
            return null;
        }
        String keyword = matcher.group(1).toLowerCase(Locale.ROOT);
        String number = normalizeNumber(matcher.group(2));
        return new ScopeHint(NODE_TYPE_BY_KEYWORD.get(keyword), number);
    }

    private boolean matchesNumber(DocumentNode node, String number) {
        String haystack = normalize(valueOrEmpty(node.getTitle()) + " " + valueOrEmpty(node.getSectionPath()));
        return haystack.contains(" " + number + " ")
                || haystack.contains(" " + number + ".")
                || haystack.endsWith(" " + number)
                || haystack.contains("chuong " + number)
                || haystack.contains("phan " + number)
                || haystack.contains("muc " + number)
                || haystack.contains("section " + number)
                || haystack.contains("chapter " + number)
                || haystack.contains("part " + number);
    }

    private int lexicalScore(String normalizedQuestion, DocumentNode node) {
        String nodeText = normalize(valueOrEmpty(node.getTitle()) + " " + valueOrEmpty(node.getSectionPath()));
        int score = 0;
        for (String token : normalizedQuestion.split("\\s+")) {
            if (token.length() < 3 && !token.matches("\\d+")) {
                continue;
            }
            if (nodeText.contains(token)) {
                score++;
            }
        }
        return score;
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

    private int safeOrder(DocumentNode node) {
        return node.getOrderIndex() == null ? Integer.MAX_VALUE : node.getOrderIndex();
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

    private record ScopeHint(String nodeType, String number) {
    }

    private record ScoredNode(DocumentNode node, int score) {
    }
}
