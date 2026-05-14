package com.example.teacherassistantai.service.agent;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.integration.ai.AiChatGateway;
import com.example.teacherassistantai.integration.ai.AiWorkload;
import com.example.teacherassistantai.service.RagConfidenceService;
import com.example.teacherassistantai.service.RagPromptBuilderService;
import com.example.teacherassistantai.service.VectorRetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FactualQaAgent {

    private final VectorRetrievalService retrievalService;
    private final RagPromptBuilderService promptBuilderService;
    private final AiChatGateway aiChatGateway;
    private final RagConfidenceService confidenceService;
    private final RagProperties ragProperties;

    public AgentResult execute(RagChatState state) {
        int topK = state.getTopK() > 0 ? state.getTopK() : ragProperties.getTopK();

        List<DocumentChunk> sources = retrievalService.retrieve(
                state.getSession(), state.getQuestion(), topK);

        String prompt = promptBuilderService.buildPrompt(
                state.getQuestion(), state.getHistory(), sources);

        String answer = aiChatGateway.generateAnswer(prompt, state.getRequestedTemperature(), AiWorkload.RAG_CHAT);

        double score = confidenceService.score(state.getQuestion(), sources, answer);
        String level = confidenceService.level(score);

        if ("LOW".equals(level) && state.getRetryCount() < 1) {
            int retryTopK = (int) Math.round(topK * 1.5);
            log.debug("FactualQaAgent: confidence LOW, retrying with topK={}", retryTopK);
            RagChatState retryState = state.toBuilder()
                    .topK(retryTopK)
                    .retryCount(state.getRetryCount() + 1)
                    .build();
            return execute(retryState);
        }

        return new AgentResult(answer, sources, score, level, false, false);
    }
}
