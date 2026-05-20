package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.ChatSessionType;
import com.example.teacherassistantai.common.enumerate.MessageRole;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.dto.request.SendChatMessageRequest;
import com.example.teacherassistantai.dto.response.ChatMessageResponse;
import com.example.teacherassistantai.entity.ChatMessage;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.repository.AgentLogRepository;
import com.example.teacherassistantai.repository.ChatMessageRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import com.example.teacherassistantai.service.agent.AgentResult;
import com.example.teacherassistantai.service.agent.DocumentScopeAgent;
import com.example.teacherassistantai.service.agent.FactualQaAgent;
import com.example.teacherassistantai.service.agent.QuizAgent;
import com.example.teacherassistantai.service.agent.RagChatOrchestrator;
import com.example.teacherassistantai.service.agent.SummaryAgent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagChatServiceArtifactIntentTest {

    @Test
    void sendMessage_routesSummaryIntentThroughSummaryAgentAndSkipsFactualQa() {
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
        FactualQaAgent factualQaAgent = mock(FactualQaAgent.class);
        RagIntentRouterService intentRouterService = mock(RagIntentRouterService.class);
        DocumentScopeAgent documentScopeAgent = mock(DocumentScopeAgent.class);
        SummaryAgent summaryAgent = mock(SummaryAgent.class);

        RagChatOrchestrator orchestrator = new RagChatOrchestrator(
                chatSessionService,
                chatMessageRepository,
                mock(AgentLogRepository.class),
                new RagProperties(),
                intentRouterService,
                new RagFollowUpResolverService(chatMessageRepository),
                mock(DocumentRepository.class),
                factualQaAgent,
                documentScopeAgent,
                summaryAgent,
                mock(QuizAgent.class),
                new SourceAttributionFormatter(),
                new InternalCitationSanitizer()
        );

        ChatSession session = session();
        SendChatMessageRequest request = new SendChatMessageRequest();
        request.setQuestion("Tóm tắt Chương 1");

        DocumentNode resolvedNode = DocumentNode.builder()
                .nodeType("chapter")
                .title("Chương 1")
                .sectionPath("Chương 1")
                .build();
        resolvedNode.setId(101L);

        when(chatSessionService.getOwnedSession(5L)).thenReturn(session);
        when(intentRouterService.route("Tóm tắt Chương 1")).thenReturn(RagChatIntent.SECTION_SUMMARY);
        when(documentScopeAgent.resolveDetailed(session, "Tóm tắt Chương 1"))
                .thenReturn(ScopeResolution.resolved(resolvedNode, 0.95, "test", List.of(resolvedNode)));
        when(summaryAgent.execute(any())).thenReturn(AgentResult.hit("Tóm tắt từ artifact", List.of()));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            message.setId(message.getRole() == MessageRole.USER ? 10L : 11L);
            return message;
        });

        ChatMessageResponse response = orchestrator.execute(5L, request);

        assertThat(response.getContent()).isEqualTo("Tóm tắt từ artifact");
        assertThat(response.getConfidenceLevel()).isEqualTo("HIGH");
        verify(factualQaAgent, never()).execute(any());
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
