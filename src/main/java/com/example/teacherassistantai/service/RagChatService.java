package com.example.teacherassistantai.service;

import com.example.teacherassistantai.dto.request.SendChatMessageRequest;
import com.example.teacherassistantai.dto.response.ChatMessageResponse;
import com.example.teacherassistantai.service.agent.RagChatOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RagChatService {

    private final RagChatOrchestrator orchestrator;

    @Transactional
    public ChatMessageResponse sendMessage(Long sessionId, SendChatMessageRequest request) {
        return orchestrator.execute(sessionId, request);
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getHistory(Long sessionId) {
        return orchestrator.getHistory(sessionId);
    }
}
