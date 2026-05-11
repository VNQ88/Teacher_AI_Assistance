package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.ChatSessionType;
import com.example.teacherassistantai.common.enumerate.MessageRole;
import com.example.teacherassistantai.common.enumerate.SubjectType;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.dto.request.SendChatMessageRequest;
import com.example.teacherassistantai.dto.response.ChatMessageResponse;
import com.example.teacherassistantai.entity.ChatMessage;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
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
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagChatServiceSourceTrackingTest {

    @Test
    void sendMessage_shouldReturnDetailedSourceMetadataForCitations() {
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
        AgentLogRepository agentLogRepository = mock(AgentLogRepository.class);
        FactualQaAgent factualQaAgent = mock(FactualQaAgent.class);

        RagProperties ragProperties = new RagProperties();
        ragProperties.setTopK(2);
        ragProperties.setMaxHistoryMessages(5);

        RagChatOrchestrator orchestrator = new RagChatOrchestrator(
                chatSessionService,
                chatMessageRepository,
                agentLogRepository,
                ragProperties,
                mock(RagIntentRouterService.class),
                mock(DocumentRepository.class),
                factualQaAgent,
                mock(DocumentScopeAgent.class),
                mock(SummaryAgent.class),
                mock(QuizAgent.class)
        );

        ChatSession session = session();
        DocumentChunk source = sourceChunk();
        SendChatMessageRequest request = new SendChatMessageRequest();
        request.setQuestion("Khái niệm vật chất là gì?");
        request.setTopK(1);
        request.setTemperature(0.2);

        when(chatSessionService.getOwnedSession(5L)).thenReturn(session);
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            if (message.getId() == null) {
                message.setId(message.getRole() == MessageRole.USER ? 10L : 11L);
            }
            return message;
        });
        when(chatMessageRepository.findBySessionIdOrderByCreatedAtDesc(any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(factualQaAgent.execute(any()))
                .thenReturn(new AgentResult("Vật chất là ... [Source 1, pages 12-13]",
                        List.of(source), 0.82, "HIGH", false, false));

        ChatMessageResponse response = orchestrator.execute(5L, request);

        assertThat(response.getSources()).containsExactly("Giao trinh Triet hoc");
        assertThat(response.getSourceDetails()).hasSize(1);
        assertThat(response.getSourceDetails().getFirst().getSourceIndex()).isEqualTo(1);
        assertThat(response.getSourceDetails().getFirst().getChunkId()).isEqualTo(99L);
        assertThat(response.getSourceDetails().getFirst().getDocumentId()).isEqualTo(42L);
        assertThat(response.getSourceDetails().getFirst().getDocumentTitle()).isEqualTo("Giao trinh Triet hoc");
        assertThat(response.getSourceDetails().getFirst().getSectionPath()).isEqualTo("Chuong 1 > I. Khai niem");
        assertThat(response.getSourceDetails().getFirst().getPageFrom()).isEqualTo(12);
        assertThat(response.getSourceDetails().getFirst().getPageTo()).isEqualTo(13);
        assertThat(response.getSourceDetails().getFirst().getChunkType()).isEqualTo("TEXT");
        assertThat(response.getSourceDetails().getFirst().getCharStart()).isEqualTo(100);
        assertThat(response.getSourceDetails().getFirst().getCharEnd()).isEqualTo(250);
        assertThat(response.getSourceDetails().getFirst().getSnippet()).contains("Nội dung nguồn");
    }

    @Test
    void getHistory_shouldReturnSourceDetailsFromStoredMessageSourceChunks() {
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
        RagChatOrchestrator orchestrator = new RagChatOrchestrator(
                chatSessionService,
                chatMessageRepository,
                mock(AgentLogRepository.class),
                new RagProperties(),
                mock(RagIntentRouterService.class),
                mock(DocumentRepository.class),
                mock(FactualQaAgent.class),
                mock(DocumentScopeAgent.class),
                mock(SummaryAgent.class),
                mock(QuizAgent.class)
        );

        ChatSession session = session();
        ChatMessage assistantMessage = ChatMessage.builder()
                .session(session)
                .role(MessageRole.ASSISTANT)
                .content("Câu trả lời đã lưu [Source 1].")
                .sourceChunks(List.of(sourceChunk()))
                .build();
        assistantMessage.setId(12L);

        when(chatSessionService.getOwnedSession(5L)).thenReturn(session);
        when(chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(5L))
                .thenReturn(List.of(assistantMessage));

        List<ChatMessageResponse> history = orchestrator.getHistory(5L);

        assertThat(history).hasSize(1);
        assertThat(history.getFirst().getSourceDetails()).hasSize(1);
        assertThat(history.getFirst().getSourceDetails().getFirst().getChunkId()).isEqualTo(99L);
        assertThat(history.getFirst().getSourceDetails().getFirst().getSectionPath()).isEqualTo("Chuong 1 > I. Khai niem");
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
                .name("Triet hoc")
                .code("TRIET")
                .subjectType(SubjectType.TEXT_BASED)
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

    private DocumentChunk sourceChunk() {
        Document document = Document.builder()
                .title("Giao trinh Triet hoc")
                .originalObjectKey("uploads/source.pdf")
                .fileType("PDF")
                .build();
        document.setId(42L);

        DocumentChunk chunk = DocumentChunk.builder()
                .document(document)
                .chunkType("TEXT")
                .sectionPath("Chuong 1 > I. Khai niem")
                .pageFrom(12)
                .pageTo(13)
                .content("Nội dung nguồn dùng để trích dẫn trong câu trả lời.")
                .metadataJsonb(Map.of("charStart", 100, "charEnd", 250))
                .build();
        chunk.setId(99L);
        return chunk;
    }
}
