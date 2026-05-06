package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.ChatSessionType;
import com.example.teacherassistantai.common.enumerate.MessageRole;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.dto.request.SendChatMessageRequest;
import com.example.teacherassistantai.dto.response.ChatMessageResponse;
import com.example.teacherassistantai.entity.ChatMessage;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.integration.ai.AiChatGateway;
import com.example.teacherassistantai.repository.AgentLogRepository;
import com.example.teacherassistantai.repository.ChatMessageRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagChatServiceArtifactIntentTest {

    @Test
    void sendMessage_usesArtifactHandlerForSummaryIntentAndSkipsVectorRetrieval() {
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
        VectorRetrievalService retrievalService = mock(VectorRetrievalService.class);
        RagIntentRouterService intentRouterService = mock(RagIntentRouterService.class);
        RagArtifactChatHandlerService artifactHandlerService = mock(RagArtifactChatHandlerService.class);
        AiChatGateway aiChatGateway = mock(AiChatGateway.class);

        RagChatService service = new RagChatService(
                chatSessionService,
                chatMessageRepository,
                retrievalService,
                mock(RagPromptBuilderService.class),
                mock(RagConfidenceService.class),
                aiChatGateway,
                mock(AgentLogRepository.class),
                new RagProperties(),
                intentRouterService,
                artifactHandlerService
        );
        ChatSession session = session();
        SendChatMessageRequest request = new SendChatMessageRequest();
        request.setQuestion("Tóm tắt Chương 1");

        when(chatSessionService.getOwnedSession(5L)).thenReturn(session);
        when(intentRouterService.route("Tóm tắt Chương 1")).thenReturn(RagChatIntent.SECTION_SUMMARY);
        when(artifactHandlerService.handle(session, "Tóm tắt Chương 1", RagChatIntent.SECTION_SUMMARY))
                .thenReturn(new RagArtifactChatHandlerService.ArtifactChatResult(
                        "Tóm tắt từ artifact",
                        List.of(),
                        1.0,
                        "HIGH",
                        true
                ));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            message.setId(message.getRole() == MessageRole.USER ? 10L : 11L);
            return message;
        });

        ChatMessageResponse response = service.sendMessage(5L, request);

        assertThat(response.getContent()).isEqualTo("Tóm tắt từ artifact");
        assertThat(response.getConfidenceLevel()).isEqualTo("HIGH");
        verify(retrievalService, never()).retrieve(any(), any(), eq(6));
        verify(aiChatGateway, never()).generateAnswer(any(), any());
    }

    private ChatSession session() {
        User user = User.builder()
                .email("student@example.com")
                .fullName("Student")
                .password("secret")
                .enabled(true)
                .build();
        user.setId(3L);
        Subject subject = Subject.builder()
                .name("Triết học")
                .code("TRIET")
                .build();
        subject.setId(7L);
        ChatSession session = ChatSession.builder()
                .user(user)
                .subject(subject)
                .sessionType(ChatSessionType.KNOWLEDGE_QA)
                .active(true)
                .build();
        session.setId(5L);
        return session;
    }
}
