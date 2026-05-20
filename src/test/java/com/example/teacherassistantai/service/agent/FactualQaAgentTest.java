package com.example.teacherassistantai.service.agent;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.ChatMessage;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.integration.ai.AiChatGateway;
import com.example.teacherassistantai.integration.ai.AiWorkload;
import com.example.teacherassistantai.service.RagConfidenceService;
import com.example.teacherassistantai.service.RagPromptBuilderService;
import com.example.teacherassistantai.service.VectorRetrievalService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FactualQaAgentTest {

    @Test
    void execute_shouldUseEffectiveQuestionForRetrievalPromptAndConfidence() {
        VectorRetrievalService retrievalService = mock(VectorRetrievalService.class);
        RagPromptBuilderService promptBuilderService = mock(RagPromptBuilderService.class);
        AiChatGateway aiChatGateway = mock(AiChatGateway.class);
        RagConfidenceService confidenceService = mock(RagConfidenceService.class);
        RagProperties ragProperties = new RagProperties();
        ragProperties.setTopK(3);
        FactualQaAgent agent = new FactualQaAgent(
                retrievalService,
                promptBuilderService,
                aiChatGateway,
                confidenceService,
                ragProperties
        );

        ChatSession session = ChatSession.builder().build();
        session.setId(5L);
        ChatMessage historyMessage = ChatMessage.builder()
                .session(session)
                .content("Câu trả lời trước")
                .build();
        DocumentChunk source = DocumentChunk.builder()
                .content("Nguồn về công cuộc đổi mới")
                .build();
        List<ChatMessage> history = List.of(historyMessage);
        List<DocumentChunk> sources = List.of(source);
        String effectiveQuestion = "Chủ đề đang trao đổi: Thành tựu và bài học của công cuộc đổi mới\n"
                + "Yêu cầu tiếp theo: Chi tiết hơn";
        RagChatState state = RagChatState.builder()
                .session(session)
                .question("Chi tiết hơn")
                .originalQuestion("Chi tiết hơn")
                .effectiveQuestion(effectiveQuestion)
                .followUp(true)
                .history(history)
                .topK(3)
                .requestedTemperature(0.2)
                .build();

        when(retrievalService.retrieve(session, effectiveQuestion, 3)).thenReturn(sources);
        when(promptBuilderService.buildPrompt("Chi tiết hơn", effectiveQuestion, true, history, sources)).thenReturn("prompt");
        when(aiChatGateway.generateAnswer("prompt", 0.2, AiWorkload.RAG_CHAT)).thenReturn("answer");
        when(confidenceService.score(effectiveQuestion, sources, "answer")).thenReturn(0.81);
        when(confidenceService.level(0.81)).thenReturn("HIGH");

        AgentResult result = agent.execute(state);

        assertThat(result.answer()).isEqualTo("answer");
        assertThat(result.sources()).containsExactly(source);
        assertThat(result.confidenceScore()).isEqualTo(0.81);
        assertThat(result.confidenceLevel()).isEqualTo("HIGH");
        verify(retrievalService).retrieve(session, effectiveQuestion, 3);
        verify(promptBuilderService).buildPrompt("Chi tiết hơn", effectiveQuestion, true, history, sources);
        verify(confidenceService).score(effectiveQuestion, sources, "answer");
    }

    @Test
    void execute_shouldPreferAnchoredSourcesThenAppendDeduplicatedRetrievedSources() {
        VectorRetrievalService retrievalService = mock(VectorRetrievalService.class);
        RagPromptBuilderService promptBuilderService = mock(RagPromptBuilderService.class);
        AiChatGateway aiChatGateway = mock(AiChatGateway.class);
        RagConfidenceService confidenceService = mock(RagConfidenceService.class);
        FactualQaAgent agent = new FactualQaAgent(
                retrievalService,
                promptBuilderService,
                aiChatGateway,
                confidenceService,
                new RagProperties()
        );

        ChatSession session = ChatSession.builder().build();
        session.setId(5L);
        DocumentChunk anchoredOne = chunk(1L, "Nguồn cũ 1");
        DocumentChunk anchoredTwo = chunk(2L, "Nguồn cũ 2");
        DocumentChunk duplicateRetrievedTwo = chunk(2L, "Nguồn retrieve trùng id 2");
        DocumentChunk retrievedThree = chunk(3L, "Nguồn retrieve 3");
        DocumentChunk retrievedFour = chunk(4L, "Nguồn retrieve 4");
        List<DocumentChunk> anchoredSources = List.of(anchoredOne, anchoredTwo);
        List<DocumentChunk> retrievedSources = List.of(duplicateRetrievedTwo, retrievedThree, retrievedFour);
        List<DocumentChunk> expectedContextSources = List.of(anchoredOne, anchoredTwo, retrievedThree);
        String effectiveQuestion = "Chủ đề đang trao đổi: Thành tựu đổi mới\nYêu cầu tiếp theo: Chi tiết hơn";
        RagChatState state = RagChatState.builder()
                .session(session)
                .question("Chi tiết hơn")
                .effectiveQuestion(effectiveQuestion)
                .followUp(true)
                .anchoredSourceChunks(anchoredSources)
                .topK(3)
                .requestedTemperature(0.2)
                .build();

        when(retrievalService.retrieve(session, effectiveQuestion, 3)).thenReturn(retrievedSources);
        when(promptBuilderService.buildPrompt("Chi tiết hơn", effectiveQuestion, true, null, expectedContextSources)).thenReturn("prompt");
        when(aiChatGateway.generateAnswer("prompt", 0.2, AiWorkload.RAG_CHAT)).thenReturn("answer");
        when(confidenceService.score(effectiveQuestion, expectedContextSources, "answer")).thenReturn(0.82);
        when(confidenceService.level(0.82)).thenReturn("HIGH");

        AgentResult result = agent.execute(state);

        assertThat(result.sources()).containsExactly(anchoredOne, anchoredTwo, retrievedThree);
        verify(promptBuilderService).buildPrompt("Chi tiết hơn", effectiveQuestion, true, null, expectedContextSources);
        verify(confidenceService).score(effectiveQuestion, expectedContextSources, "answer");
    }

    @Test
    void execute_shouldNotRetryLowConfidenceFollowUpWhenAnchoredSourcesExist() {
        VectorRetrievalService retrievalService = mock(VectorRetrievalService.class);
        RagPromptBuilderService promptBuilderService = mock(RagPromptBuilderService.class);
        AiChatGateway aiChatGateway = mock(AiChatGateway.class);
        RagConfidenceService confidenceService = mock(RagConfidenceService.class);
        FactualQaAgent agent = new FactualQaAgent(
                retrievalService,
                promptBuilderService,
                aiChatGateway,
                confidenceService,
                new RagProperties()
        );

        ChatSession session = ChatSession.builder().build();
        session.setId(5L);
        DocumentChunk anchored = chunk(1L, "Nguồn cũ");
        DocumentChunk retrieved = chunk(2L, "Nguồn mới");
        List<DocumentChunk> contextSources = List.of(anchored, retrieved);
        String effectiveQuestion = "Chủ đề đang trao đổi: Thành tựu đổi mới\nYêu cầu tiếp theo: Chi tiết hơn";
        RagChatState state = RagChatState.builder()
                .session(session)
                .question("Chi tiết hơn")
                .originalQuestion("Chi tiết hơn")
                .effectiveQuestion(effectiveQuestion)
                .followUp(true)
                .anchoredSourceChunks(List.of(anchored))
                .topK(3)
                .requestedTemperature(0.2)
                .build();

        when(retrievalService.retrieve(session, effectiveQuestion, 3)).thenReturn(List.of(retrieved));
        when(promptBuilderService.buildPrompt("Chi tiết hơn", effectiveQuestion, true, null, contextSources)).thenReturn("prompt");
        when(aiChatGateway.generateAnswer("prompt", 0.2, AiWorkload.RAG_CHAT)).thenReturn("answer");
        when(confidenceService.score(effectiveQuestion, contextSources, "answer")).thenReturn(0.30);
        when(confidenceService.level(0.30)).thenReturn("LOW");

        AgentResult result = agent.execute(state);

        assertThat(result.confidenceLevel()).isEqualTo("LOW");
        assertThat(result.sources()).containsExactly(anchored, retrieved);
        verify(retrievalService, times(1)).retrieve(session, effectiveQuestion, 3);
        verify(retrievalService, never()).retrieve(session, effectiveQuestion, 5);
        verify(aiChatGateway, times(1)).generateAnswer("prompt", 0.2, AiWorkload.RAG_CHAT);
    }

    private DocumentChunk chunk(Long id, String content) {
        DocumentChunk chunk = DocumentChunk.builder()
                .content(content)
                .build();
        chunk.setId(id);
        return chunk;
    }
}
