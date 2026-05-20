package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.MessageRole;
import com.example.teacherassistantai.entity.ChatMessage;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RagFollowUpResolverService {

    private static final int MAX_PREVIOUS_ANSWER_CHARS = 700;

    private static final List<String> EXACT_FOLLOW_UPS = List.of(
            "chi tiet hon",
            "noi ro hon",
            "giai thich them",
            "giai thich ky hon",
            "phan tich them",
            "cu the hon",
            "mo rong them",
            "cho vi du",
            "lay vi du",
            "vi du",
            "tai sao",
            "tai sao vay"
    );

    private static final List<String> REFERENCE_PHRASES = List.of(
            "y nay",
            "dieu nay",
            "noi dung nay",
            "phan nay",
            "doan nay",
            "cau nay",
            "cai nay",
            "no nghia",
            "no co nghia",
            "nghia la sao"
    );

    private static final List<String> SHORT_FOLLOW_UP_ACTIONS = List.of(
            "chi tiet",
            "ro hon",
            "giai thich",
            "phan tich",
            "cu the",
            "mo rong"
    );

    private final ChatMessageRepository chatMessageRepository;

    public FollowUpResolution resolve(ChatSession session, ChatMessage currentUserMessage, String originalQuestion) {
        if (!isFollowUp(originalQuestion) || session == null || currentUserMessage == null || currentUserMessage.getId() == null) {
            return FollowUpResolution.standalone(originalQuestion);
        }

        Optional<ChatMessage> previousUser = chatMessageRepository
                .findTopBySessionIdAndRoleAndIdLessThanOrderByIdDesc(
                        session.getId(), MessageRole.USER, currentUserMessage.getId());
        Optional<ChatMessage> previousAssistant = chatMessageRepository
                .findTopBySessionIdAndRoleAndIdLessThanOrderByIdDesc(
                        session.getId(), MessageRole.ASSISTANT, currentUserMessage.getId());

        if (previousUser.isEmpty() && previousAssistant.isEmpty()) {
            return FollowUpResolution.standalone(originalQuestion);
        }

        String effectiveQuestion = buildEffectiveQuestion(
                originalQuestion,
                previousUser.map(ChatMessage::getContent).orElse(""),
                previousAssistant.map(ChatMessage::getContent).orElse("")
        );
        return new FollowUpResolution(
                originalQuestion,
                effectiveQuestion,
                true,
                previousUser.orElse(null),
                previousAssistant.orElse(null),
                previousAssistant.map(ChatMessage::getSourceChunks).orElse(List.of())
        );
    }

    private boolean isFollowUp(String question) {
        String normalized = normalize(question);
        if (normalized.isBlank()) {
            return false;
        }

        for (String exact : EXACT_FOLLOW_UPS) {
            if (normalized.equals(exact)) {
                return true;
            }
        }

        int tokenCount = tokenCount(normalized);
        if (tokenCount <= 12 && containsAny(normalized, REFERENCE_PHRASES)) {
            return true;
        }
        return tokenCount <= 6 && containsAny(normalized, SHORT_FOLLOW_UP_ACTIONS);
    }

    private String buildEffectiveQuestion(String originalQuestion, String previousQuestion, String previousAnswer) {
        StringBuilder builder = new StringBuilder();
        if (hasText(previousQuestion)) {
            builder.append("Chủ đề đang trao đổi: ").append(clean(previousQuestion));
        }

        String previousAnswerSnippet = snippet(stripAssistantFooter(previousAnswer), MAX_PREVIOUS_ANSWER_CHARS);
        if (hasText(previousAnswerSnippet)) {
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            builder.append("Nội dung câu trả lời trước: ").append(previousAnswerSnippet);
        }

        if (!builder.isEmpty()) {
            builder.append("\n");
        }
        builder.append("Yêu cầu tiếp theo: ").append(clean(originalQuestion));
        return builder.toString();
    }

    private String stripAssistantFooter(String content) {
        if (!hasText(content)) {
            return "";
        }
        String lower = content.toLowerCase(Locale.ROOT);
        int footerIndex = firstFooterIndex(lower);
        return footerIndex < 0 ? content : content.substring(0, footerIndex);
    }

    private int firstFooterIndex(String lowerContent) {
        int first = -1;
        for (String marker : List.of("câu hỏi tiếp theo", "cau hoi tiep theo", "nguồn tham khảo", "nguon tham khao")) {
            int index = lowerContent.indexOf(marker);
            if (index >= 0 && (first < 0 || index < first)) {
                first = index;
            }
        }
        return first;
    }

    private String snippet(String value, int maxChars) {
        String cleaned = clean(value);
        if (cleaned.length() <= maxChars) {
            return cleaned;
        }
        return cleaned.substring(0, maxChars - 3).trim() + "...";
    }

    private boolean containsAny(String text, List<String> phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private int tokenCount(String normalized) {
        if (normalized.isBlank()) {
            return 0;
        }
        return normalized.split("\\s+").length;
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

    private String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record FollowUpResolution(
            String originalQuestion,
            String effectiveQuestion,
            boolean followUp,
            ChatMessage previousUserMessage,
            ChatMessage previousAssistantMessage,
            List<DocumentChunk> previousSourceChunks
    ) {
        static FollowUpResolution standalone(String question) {
            return new FollowUpResolution(question, question, false, null, null, List.of());
        }
    }
}
