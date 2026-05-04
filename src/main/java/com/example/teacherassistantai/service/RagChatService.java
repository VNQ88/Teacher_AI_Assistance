package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.AgentType;
import com.example.teacherassistantai.common.enumerate.MessageRole;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.dto.request.SendChatMessageRequest;
import com.example.teacherassistantai.dto.response.ChatMessageResponse;
import com.example.teacherassistantai.dto.response.SourceChunkResponse;
import com.example.teacherassistantai.entity.AgentLog;
import com.example.teacherassistantai.entity.ChatMessage;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.integration.ai.AiChatGateway;
import com.example.teacherassistantai.repository.AgentLogRepository;
import com.example.teacherassistantai.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RagChatService {

    private final ChatSessionService chatSessionService;
    private final ChatMessageRepository chatMessageRepository;
    private final VectorRetrievalService retrievalService;
    private final RagPromptBuilderService promptBuilderService;
    private final RagConfidenceService confidenceService;
    private final AiChatGateway aiChatGateway;
    private final AgentLogRepository agentLogRepository;
    private final RagProperties ragProperties;

    @Transactional
    public ChatMessageResponse sendMessage(Long sessionId, SendChatMessageRequest request) {
        long startedAt = System.currentTimeMillis();
        ChatSession session = chatSessionService.getOwnedSession(sessionId);

        ChatMessage userMessage = ChatMessage.builder()
                .session(session)
                .role(MessageRole.USER)
                .content(request.getQuestion())
                .build();
        chatMessageRepository.save(userMessage);

        int topK = request.getTopK() != null ? request.getTopK() : ragProperties.getTopK();
        List<DocumentChunk> sources = retrievalService.retrieve(session, request.getQuestion(), topK);

        List<ChatMessage> history = loadHistory(session.getId(), request.getQuestion(), ragProperties.getMaxHistoryMessages());
        String prompt = promptBuilderService.buildPrompt(request.getQuestion(), history, sources);
        String answer = aiChatGateway.generateAnswer(prompt, request.getTemperature());

        double confidenceScore = confidenceService.score(request.getQuestion(), sources, answer);
        String confidenceLevel = confidenceService.level(confidenceScore);

        ChatMessage assistantMessage = ChatMessage.builder()
                .session(session)
                .role(MessageRole.ASSISTANT)
                .content(answer)
                .agentType(AgentType.KNOWLEDGE_CHATBOT)
                .sourceChunks(sources)
                .responseTimeMs(System.currentTimeMillis() - startedAt)
                .tokensUsed(estimateTokens(prompt, answer))
                .build();
        ChatMessage savedAssistant = chatMessageRepository.save(assistantMessage);

        agentLogRepository.save(AgentLog.builder()
                .agentType(AgentType.KNOWLEDGE_CHATBOT)
                .triggeredBy(session.getUser())
                .subject(session.getSubject())
                .success(true)
                .inputSummary(request.getQuestion())
                .outputSummary(answer)
                .processingTimeMs(savedAssistant.getResponseTimeMs())
                .tokensUsed(savedAssistant.getTokensUsed())
                .relatedEntityType("ChatSession")
                .relatedEntityId(session.getId())
                .build());

        return toResponse(savedAssistant, confidenceScore, confidenceLevel);
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getHistory(Long sessionId) {
        ChatSession session = chatSessionService.getOwnedSession(sessionId);
        return chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private List<ChatMessage> loadHistory(Long sessionId, String question, int maxMessages) {
        int historyLimit = Math.max(1, Math.min(5, maxMessages));
        int recentWindow = Math.max(10, historyLimit * 4);

        Page<ChatMessage> page = chatMessageRepository.findBySessionIdOrderByCreatedAtDesc(
                sessionId,
                PageRequest.of(0, recentWindow)
        );

        Set<String> questionTokens = normalizeTokens(question);
        return page.getContent().stream()
                .filter(message -> message.getRole() != MessageRole.USER)
                .sorted(Comparator
                        .comparingDouble((ChatMessage message) -> overlapScore(questionTokens, message.getContent())).reversed()
                        .thenComparing(ChatMessage::getCreatedAt, Comparator.reverseOrder()))
                .limit(historyLimit)
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt))
                .toList();
    }

    private double overlapScore(Set<String> questionTokens, String text) {
        if (questionTokens.isEmpty() || text == null || text.isBlank()) {
            return 0.0;
        }
        Set<String> messageTokens = normalizeTokens(text);
        int overlap = 0;
        for (String token : questionTokens) {
            if (messageTokens.contains(token)) {
                overlap++;
            }
        }
        return (double) overlap / questionTokens.size();
    }

    private Set<String> normalizeTokens(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String[] raw = text.toLowerCase().replaceAll("[^\\p{L}\\p{N}\\s]", " ").trim().split("\\s+");
        Set<String> tokens = new HashSet<>();
        for (String token : raw) {
            if (token.length() >= 2 || token.chars().allMatch(Character::isDigit)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private int estimateTokens(String prompt, String answer) {
        int promptTokens = prompt == null ? 0 : Math.max(1, prompt.length() / 4);
        int answerTokens = answer == null ? 0 : Math.max(1, answer.length() / 4);
        return promptTokens + answerTokens;
    }

    private ChatMessageResponse toResponse(ChatMessage message) {
        return toResponse(message, null, null);
    }

    private ChatMessageResponse toResponse(ChatMessage message, Double confidenceScore, String confidenceLevel) {
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

    private List<String> extractDistinctDocumentTitles(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        Set<String> titles = new LinkedHashSet<>();
        for (DocumentChunk chunk : chunks) {
            if (chunk.getDocument() == null) {
                continue;
            }
            String title = chunk.getDocument().getTitle();
            if (title != null && !title.isBlank()) {
                titles.add(title);
            }
        }
        return new ArrayList<>(titles);
    }

    private List<SourceChunkResponse> toSourceDetails(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        List<SourceChunkResponse> details = new ArrayList<>();
        Set<Long> seenChunkIds = new HashSet<>();
        for (DocumentChunk chunk : chunks) {
            Long chunkId = chunk.getId();
            if (chunkId != null && !seenChunkIds.add(chunkId)) {
                continue;
            }
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
        if (metadata == null || !metadata.containsKey(key)) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String stringValue && stringValue.matches("-?\\d+")) {
            return Integer.parseInt(stringValue);
        }
        return null;
    }

    private String snippet(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 320) {
            return normalized;
        }
        return normalized.substring(0, 317) + "...";
    }
}
