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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

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
        String retrievalQuestion = questionForRetrieval(state);

        List<DocumentChunk> sources = retrievalService.retrieve(
                state.getSession(), retrievalQuestion, topK);
        List<DocumentChunk> contextSources = mergeSources(
                state.getAnchoredSourceChunks(), sources, topK);

        String prompt = promptBuilderService.buildPrompt(
                originalQuestion(state),
                retrievalQuestion,
                state.isFollowUp(),
                state.getHistory(),
                contextSources);

        String answer = aiChatGateway.generateAnswer(prompt, state.getRequestedTemperature(), AiWorkload.RAG_CHAT);

        double score = confidenceService.score(retrievalQuestion, contextSources, answer);
        String level = confidenceService.level(score);

        if ("LOW".equals(level) && shouldRetryLowConfidence(state)) {
            int retryTopK = (int) Math.round(topK * 1.5);
            log.debug("FactualQaAgent: confidence LOW, retrying with topK={}", retryTopK);
            RagChatState retryState = state.toBuilder()
                    .topK(retryTopK)
                    .retryCount(state.getRetryCount() + 1)
                    .build();
            return execute(retryState);
        }

        return new AgentResult(answer, contextSources, score, level, false, false);
    }

    private boolean shouldRetryLowConfidence(RagChatState state) {
        if (state.getRetryCount() >= 1) {
            return false;
        }
        return !state.isFollowUp() || state.getAnchoredSourceChunks() == null || state.getAnchoredSourceChunks().isEmpty();
    }

    private String questionForRetrieval(RagChatState state) {
        String effectiveQuestion = state.getEffectiveQuestion();
        if (effectiveQuestion != null && !effectiveQuestion.isBlank()) {
            return effectiveQuestion;
        }
        return state.getQuestion();
    }

    private String originalQuestion(RagChatState state) {
        String originalQuestion = state.getOriginalQuestion();
        if (originalQuestion != null && !originalQuestion.isBlank()) {
            return originalQuestion;
        }
        return state.getQuestion();
    }

    private List<DocumentChunk> mergeSources(List<DocumentChunk> anchored,
                                             List<DocumentChunk> retrieved,
                                             int limit) {
        int safeLimit = Math.max(1, limit);
        List<DocumentChunk> merged = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();
        Set<DocumentChunk> seenInstances = Collections.newSetFromMap(new IdentityHashMap<>());

        addSources(merged, seenIds, seenInstances, anchored, safeLimit);
        addSources(merged, seenIds, seenInstances, retrieved, safeLimit);
        return merged;
    }

    private void addSources(List<DocumentChunk> merged,
                            Set<Long> seenIds,
                            Set<DocumentChunk> seenInstances,
                            List<DocumentChunk> candidates,
                            int limit) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        for (DocumentChunk candidate : candidates) {
            if (merged.size() >= limit) {
                return;
            }
            if (candidate == null || alreadySeen(candidate, seenIds, seenInstances)) {
                continue;
            }
            merged.add(candidate);
        }
    }

    private boolean alreadySeen(DocumentChunk chunk,
                                Set<Long> seenIds,
                                Set<DocumentChunk> seenInstances) {
        Long id = chunk.getId();
        if (id != null) {
            return !seenIds.add(id);
        }
        return !seenInstances.add(chunk);
    }
}
