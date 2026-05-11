package com.example.teacherassistantai.service.agent;

import com.example.teacherassistantai.common.enumerate.AgentType;
import com.example.teacherassistantai.common.enumerate.MessageRole;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.dto.request.SendChatMessageRequest;
import com.example.teacherassistantai.dto.response.ChatMessageResponse;
import com.example.teacherassistantai.dto.response.SourceChunkResponse;
import com.example.teacherassistantai.entity.AgentLog;
import com.example.teacherassistantai.entity.ChatMessage;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.repository.AgentLogRepository;
import com.example.teacherassistantai.repository.ChatMessageRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import com.example.teacherassistantai.service.ChatSessionService;
import com.example.teacherassistantai.service.RagChatIntent;
import com.example.teacherassistantai.service.RagIntentRouterService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RagChatOrchestrator {

    private final ChatSessionService chatSessionService;
    private final ChatMessageRepository chatMessageRepository;
    private final AgentLogRepository agentLogRepository;
    private final RagProperties ragProperties;
    private final RagIntentRouterService intentRouterService;
    private final DocumentRepository documentRepository;
    private final FactualQaAgent factualQaAgent;
    private final DocumentScopeAgent documentScopeAgent;
    private final SummaryAgent summaryAgent;
    private final QuizAgent quizAgent;

    public ChatMessageResponse execute(Long sessionId, SendChatMessageRequest request) {
        long startedAt = System.currentTimeMillis();
        ChatSession session = chatSessionService.getOwnedSession(sessionId);

        ChatMessage userMessage = ChatMessage.builder()
                .session(session)
                .role(MessageRole.USER)
                .content(request.getQuestion())
                .build();
        chatMessageRepository.save(userMessage);

        if (resolveProcessingDocument(session) != null) {
            ChatMessage notice = saveAssistantMessage(session,
                    "Tài liệu đang được xử lý (khoảng 10-20 phút). Bạn sẽ có thể hỏi đáp sau khi tài liệu sẵn sàng.",
                    List.of(), startedAt, 0, AgentType.KNOWLEDGE_CHATBOT);
            saveAgentLog(session, request.getQuestion(), notice, notice.getContent());
            return toResponse(notice, null, null);
        }

        RagChatIntent intent = intentRouterService.route(request.getQuestion());

        AgentResult result;
        AgentType agentType;

        if (intent == RagChatIntent.SECTION_SUMMARY) {
            Optional<DocumentNode> resolvedNode =
                    documentScopeAgent.resolve(session, request.getQuestion());
            if (resolvedNode.isEmpty()) {
                result = documentScopeAgent.notFound();
            } else {
                RagChatState state = RagChatState.builder()
                        .question(request.getQuestion())
                        .session(session)
                        .intent(intent)
                        .resolvedNode(resolvedNode.get())
                        .startedAt(startedAt)
                        .build();
                result = summaryAgent.execute(state);
            }
            agentType = AgentType.KNOWLEDGE_CHATBOT;
        } else if (intent == RagChatIntent.REVIEW_QUESTION_GENERATION) {
            Optional<DocumentNode> resolvedNode = documentScopeAgent.resolve(session, request.getQuestion());
            if (resolvedNode.isEmpty()) {
                result = documentScopeAgent.notFound();
            } else {
                RagChatState state = RagChatState.builder()
                        .question(request.getQuestion())
                        .session(session)
                        .intent(intent)
                        .resolvedNode(resolvedNode.get())
                        .startedAt(startedAt)
                        .build();
                result = quizAgent.execute(state);
            }
            agentType = AgentType.QUIZ_GENERATOR;
        } else {
            List<ChatMessage> history = loadHistory(
                    session.getId(), request.getQuestion(), ragProperties.getMaxHistoryMessages());
            int topK = request.getTopK() != null ? request.getTopK() : ragProperties.getTopK();
            RagChatState state = RagChatState.builder()
                    .question(request.getQuestion())
                    .session(session)
                    .history(history)
                    .topK(topK)
                    .retryCount(0)
                    .requestedTemperature(request.getTemperature())
                    .startedAt(startedAt)
                    .build();
            result = factualQaAgent.execute(state);
            agentType = AgentType.KNOWLEDGE_CHATBOT;
        }

        String answerWithSources = appendSourcesFooter(result.answer(), result.sources());
        int tokens = estimateTokens(request.getQuestion(), answerWithSources);
        ChatMessage savedAssistant = saveAssistantMessage(
                session, answerWithSources, result.sources(), startedAt, tokens, agentType);
        saveAgentLog(session, request.getQuestion(), savedAssistant, answerWithSources);

        return toResponse(savedAssistant, result.confidenceScore(), result.confidenceLevel());
    }

    public List<ChatMessageResponse> getHistory(Long sessionId) {
        ChatSession session = chatSessionService.getOwnedSession(sessionId);
        return chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private Document resolveProcessingDocument(ChatSession session) {
        if (session.getSubject() == null) return null;
        Long subjectId = session.getSubject().getId();
        var readyPage = documentRepository.findByFilters(subjectId, DocumentStatus.READY, PageRequest.of(0, 1));
        if (readyPage != null && readyPage.hasContent()) return null;
        var sumPage = documentRepository.findByFilters(subjectId, DocumentStatus.SUMMARISING, PageRequest.of(0, 1));
        if (sumPage == null || !sumPage.hasContent()) return null;
        return sumPage.getContent().stream().findFirst().orElse(null);
    }

    private List<ChatMessage> loadHistory(Long sessionId, String question, int maxMessages) {
        int historyLimit = Math.max(1, Math.min(5, maxMessages));
        int recentWindow = Math.max(10, historyLimit * 4);

        Page<ChatMessage> page = chatMessageRepository.findBySessionIdOrderByCreatedAtDesc(
                sessionId, PageRequest.of(0, recentWindow));

        Set<String> questionTokens = normalizeTokens(question);
        return page.getContent().stream()
                .filter(message -> message.getRole() != MessageRole.USER)
                .sorted(Comparator
                        .comparingDouble((ChatMessage m) -> overlapScore(questionTokens, m.getContent())).reversed()
                        .thenComparing(ChatMessage::getCreatedAt, Comparator.reverseOrder()))
                .limit(historyLimit)
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt))
                .toList();
    }

    private double overlapScore(Set<String> questionTokens, String text) {
        if (questionTokens.isEmpty() || text == null || text.isBlank()) return 0.0;
        Set<String> messageTokens = normalizeTokens(text);
        int overlap = 0;
        for (String token : questionTokens) {
            if (messageTokens.contains(token)) overlap++;
        }
        return (double) overlap / questionTokens.size();
    }

    private Set<String> normalizeTokens(String text) {
        if (text == null || text.isBlank()) return Set.of();
        String[] raw = text.toLowerCase().replaceAll("[^\\p{L}\\p{N}\\s]", " ").trim().split("\\s+");
        Set<String> tokens = new HashSet<>();
        for (String token : raw) {
            if (token.length() >= 2 || token.chars().allMatch(Character::isDigit)) tokens.add(token);
        }
        return tokens;
    }

    private int estimateTokens(String prompt, String answer) {
        int promptTokens = prompt == null ? 0 : Math.max(1, prompt.length() / 4);
        int answerTokens = answer == null ? 0 : Math.max(1, answer.length() / 4);
        return promptTokens + answerTokens;
    }

    private ChatMessage saveAssistantMessage(ChatSession session, String answer,
                                             List<DocumentChunk> sources, long startedAt,
                                             int tokensUsed, AgentType agentType) {
        ChatMessage assistantMessage = ChatMessage.builder()
                .session(session)
                .role(MessageRole.ASSISTANT)
                .content(answer)
                .agentType(agentType)
                .sourceChunks(sources)
                .responseTimeMs(System.currentTimeMillis() - startedAt)
                .tokensUsed(tokensUsed)
                .build();
        return chatMessageRepository.save(assistantMessage);
    }

    private void saveAgentLog(ChatSession session, String question,
                               ChatMessage savedAssistant, String answer) {
        agentLogRepository.save(AgentLog.builder()
                .agentType(savedAssistant.getAgentType())
                .triggeredBy(session.getUser())
                .subject(session.getSubject())
                .success(true)
                .inputSummary(question)
                .outputSummary(answer)
                .processingTimeMs(savedAssistant.getResponseTimeMs())
                .tokensUsed(savedAssistant.getTokensUsed())
                .relatedEntityType("ChatSession")
                .relatedEntityId(session.getId())
                .build());
    }

    private ChatMessageResponse toResponse(ChatMessage message) {
        return toResponse(message, null, null);
    }

    private ChatMessageResponse toResponse(ChatMessage message,
                                            Double confidenceScore, String confidenceLevel) {
        return ChatMessageResponse.builder()
                .id(message.getId())
                .role(message.getRole())
                .content(message.getContent())
                .agentType(message.getAgentType())
                .confidenceScore(confidenceScore)
                .confidenceLevel(confidenceLevel)
                .tokensUsed(message.getTokensUsed())
                .responseTimeMs(message.getResponseTimeMs())
                .sources(extractDistinctDocumentTitles(message.getSourceChunks()))
                .sourceDetails(toSourceDetails(message.getSourceChunks()))
                .createdAt(message.getCreatedAt())
                .build();
    }

    private String appendSourcesFooter(String answer, List<DocumentChunk> sources) {
        if (sources == null || sources.isEmpty()) return answer;
        Set<String> seen = new LinkedHashSet<>();
        for (DocumentChunk chunk : sources) {
            String title = chunk.getDocument() == null ? null : chunk.getDocument().getTitle();
            if (title == null || title.isBlank()) continue;
            String pages = pageRange(chunk.getPageFrom(), chunk.getPageTo());
            seen.add(pages.isBlank() ? title : title + " (trang " + pages + ")");
        }
        if (seen.isEmpty()) return answer;
        StringBuilder footer = new StringBuilder("\n\n---\n**Nguồn tham khảo:**");
        for (String entry : seen) {
            footer.append("\n- ").append(entry);
        }
        return answer + footer;
    }

    private String pageRange(Integer pageFrom, Integer pageTo) {
        if (pageFrom == null && pageTo == null) return "";
        if (pageFrom != null && pageTo != null && !pageFrom.equals(pageTo)) return pageFrom + "-" + pageTo;
        return String.valueOf(pageFrom != null ? pageFrom : pageTo);
    }

    private List<String> extractDistinctDocumentTitles(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return List.of();
        Set<String> titles = new LinkedHashSet<>();
        for (DocumentChunk chunk : chunks) {
            if (chunk.getDocument() == null) continue;
            String title = chunk.getDocument().getTitle();
            if (title != null && !title.isBlank()) titles.add(title);
        }
        return new ArrayList<>(titles);
    }

    private List<SourceChunkResponse> toSourceDetails(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return List.of();
        List<SourceChunkResponse> details = new ArrayList<>();
        Set<Long> seenChunkIds = new HashSet<>();
        for (DocumentChunk chunk : chunks) {
            Long chunkId = chunk.getId();
            if (chunkId != null && !seenChunkIds.add(chunkId)) continue;
            details.add(SourceChunkResponse.builder()
                    .sourceIndex(details.size() + 1)
                    .chunkId(chunkId)
                    .documentId(chunk.getDocument() == null ? null : chunk.getDocument().getId())
                    .documentTitle(chunk.getDocument() == null ? null : chunk.getDocument().getTitle())
                    .sectionPath(chunk.getSectionPath())
                    .pageFrom(chunk.getPageFrom())
                    .pageTo(chunk.getPageTo())
                    .chunkType(chunk.getChunkType())
                    .charStart(metadataInt(chunk.getMetadataJsonb(), "charStart"))
                    .charEnd(metadataInt(chunk.getMetadataJsonb(), "charEnd"))
                    .snippet(snippet(chunk.getContent()))
                    .build());
        }
        return details;
    }

    private Integer metadataInt(Map<String, Object> metadata, String key) {
        if (metadata == null || !metadata.containsKey(key)) return null;
        Object value = metadata.get(key);
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String s && s.matches("-?\\d+")) return Integer.parseInt(s);
        return null;
    }

    private String snippet(String content) {
        if (content == null || content.isBlank()) return "";
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 320 ? normalized : normalized.substring(0, 317) + "...";
    }
}
