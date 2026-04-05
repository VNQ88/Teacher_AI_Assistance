package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.AgentType;
import com.example.teacherassistantai.common.enumerate.MessageRole;
import com.example.teacherassistantai.common.response.PageResponse;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.dto.request.SendChatMessageRequest;
import com.example.teacherassistantai.dto.response.ChatMessageResponse;
import com.example.teacherassistantai.dto.response.RagAnswerResponse;
import com.example.teacherassistantai.dto.response.RagSourceResponse;
import com.example.teacherassistantai.dto.response.TokenUsageResponse;
import com.example.teacherassistantai.entity.AgentLog;
import com.example.teacherassistantai.entity.ChatMessage;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.integration.gemini.GeminiChatGateway;
import com.example.teacherassistantai.repository.AgentLogRepository;
import com.example.teacherassistantai.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RagChatService {

    private final ChatSessionService chatSessionService;
    private final ChatMessageRepository chatMessageRepository;
    private final VectorRetrievalService retrievalService;
    private final RagPromptBuilderService promptBuilderService;
    private final RagConfidenceService confidenceService;
    private final GeminiChatGateway geminiChatGateway;
    private final AgentLogRepository agentLogRepository;
    private final RagProperties ragProperties;

    @Transactional
    public RagAnswerResponse sendMessage(Long sessionId, SendChatMessageRequest request) {
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

        List<ChatMessage> history = loadHistory(session.getId(), ragProperties.getMaxHistoryMessages());
        String prompt = promptBuilderService.buildPrompt(request.getQuestion(), history, sources);
        String answer = geminiChatGateway.generateAnswer(prompt, request.getTemperature());

        double confidenceScore = confidenceService.score(sources, answer);
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

        return RagAnswerResponse.builder()
                .sessionId(session.getId())
                .messageId(savedAssistant.getId())
                .answer(answer)
                .confidenceScore(confidenceScore)
                .confidenceLevel(confidenceLevel)
                .fallback("LOW".equals(confidenceLevel))
                .lowConfidenceReason("LOW".equals(confidenceLevel) ? "INSUFFICIENT_RETRIEVAL_CONTEXT" : null)
                .suggestions("LOW".equals(confidenceLevel)
                        ? List.of("Ban co the mo ta cu the hon chu de?", "Ban muon minh uu tien tai lieu lop hoc nao?")
                        : List.of())
                .sources(toSources(sources))
                .usage(TokenUsageResponse.builder()
                        .totalTokens(savedAssistant.getTokensUsed())
                        .latencyMs(savedAssistant.getResponseTimeMs())
                        .build())
                .createdAt(savedAssistant.getCreatedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public PageResponse<?> getMessages(Long sessionId, int pageNo, int pageSize) {
        ChatSession session = chatSessionService.getOwnedSession(sessionId);
        Page<ChatMessage> page = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId(), PageRequest.of(pageNo, pageSize));
        List<ChatMessageResponse> items = page.getContent().stream().map(this::toResponse).toList();

        return PageResponse.<List<ChatMessageResponse>>builder()
                .pageNo(pageNo)
                .pageSize(pageSize)
                .totalPage(page.getTotalPages())
                .items(items)
                .build();
    }

    private List<ChatMessage> loadHistory(Long sessionId, int maxMessages) {
        Page<ChatMessage> page = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId, PageRequest.of(0, Math.max(1, maxMessages)));
        return page.getContent();
    }

    private int estimateTokens(String prompt, String answer) {
        int promptTokens = prompt == null ? 0 : Math.max(1, prompt.length() / 4);
        int answerTokens = answer == null ? 0 : Math.max(1, answer.length() / 4);
        return promptTokens + answerTokens;
    }

    private List<RagSourceResponse> toSources(List<DocumentChunk> chunks) {
        List<RagSourceResponse> sources = new ArrayList<>();
        for (DocumentChunk chunk : chunks) {
            sources.add(RagSourceResponse.builder()
                    .chunkId(chunk.getId())
                    .documentId(chunk.getDocument() != null ? chunk.getDocument().getId() : null)
                    .documentTitle(chunk.getDocument() != null ? chunk.getDocument().getTitle() : null)
                    .score(null)
                    .excerpt(excerpt(chunk.getContent(), 240))
                    .build());
        }
        return sources;
    }

    private String excerpt(String text, int maxLen) {
        if (text == null) return null;
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    private ChatMessageResponse toResponse(ChatMessage message) {
        return ChatMessageResponse.builder()
                .id(message.getId())
                .role(message.getRole())
                .content(message.getContent())
                .agentType(message.getAgentType())
                .tokensUsed(message.getTokensUsed())
                .responseTimeMs(message.getResponseTimeMs())
                .sources(message.getSourceChunks() == null ? List.of() : toSources(message.getSourceChunks()))
                .createdAt(message.getCreatedAt())
                .build();
    }
}

