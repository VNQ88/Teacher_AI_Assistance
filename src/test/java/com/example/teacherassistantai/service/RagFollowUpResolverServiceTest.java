package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.MessageRole;
import com.example.teacherassistantai.entity.ChatMessage;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.repository.ChatMessageRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagFollowUpResolverServiceTest {

    @Test
    void resolve_shouldRewriteShortFollowUpUsingPreviousTurn() {
        ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
        RagFollowUpResolverService resolverService = new RagFollowUpResolverService(chatMessageRepository);

        ChatSession session = ChatSession.builder().build();
        session.setId(5L);
        ChatMessage currentUserMessage = ChatMessage.builder()
                .session(session)
                .role(MessageRole.USER)
                .content("Chi tiết hơn")
                .build();
        currentUserMessage.setId(30L);
        ChatMessage previousUserMessage = ChatMessage.builder()
                .session(session)
                .role(MessageRole.USER)
                .content("Thành tựu và bài học của công cuộc đổi mới")
                .build();
        previousUserMessage.setId(28L);
        DocumentChunk sourceChunk = DocumentChunk.builder()
                .content("Nguồn câu trả lời trước")
                .build();
        sourceChunk.setId(99L);
        ChatMessage previousAssistantMessage = ChatMessage.builder()
                .session(session)
                .role(MessageRole.ASSISTANT)
                .content("""
                        Thành tựu của công cuộc đổi mới là to lớn và có ý nghĩa lịch sử.

                        Câu hỏi tiếp theo: Những nhiệm vụ nào đã được hoàn thành?

                        ---
                        **Nguồn tham khảo:**
                        - Giáo trình Lịch sử DCSVN 2
                        """)
                .sourceChunks(List.of(sourceChunk))
                .build();
        previousAssistantMessage.setId(29L);

        when(chatMessageRepository.findTopBySessionIdAndRoleAndIdLessThanOrderByIdDesc(
                5L, MessageRole.USER, 30L))
                .thenReturn(Optional.of(previousUserMessage));
        when(chatMessageRepository.findTopBySessionIdAndRoleAndIdLessThanOrderByIdDesc(
                5L, MessageRole.ASSISTANT, 30L))
                .thenReturn(Optional.of(previousAssistantMessage));

        RagFollowUpResolverService.FollowUpResolution resolution =
                resolverService.resolve(session, currentUserMessage, "Chi tiết hơn");

        assertThat(resolution.followUp()).isTrue();
        assertThat(resolution.originalQuestion()).isEqualTo("Chi tiết hơn");
        assertThat(resolution.effectiveQuestion())
                .contains("Chủ đề đang trao đổi: Thành tựu và bài học của công cuộc đổi mới")
                .contains("Nội dung câu trả lời trước: Thành tựu của công cuộc đổi mới")
                .contains("Yêu cầu tiếp theo: Chi tiết hơn")
                .doesNotContain("Câu hỏi tiếp theo")
                .doesNotContain("Nguồn tham khảo");
        assertThat(resolution.previousUserMessage()).isSameAs(previousUserMessage);
        assertThat(resolution.previousAssistantMessage()).isSameAs(previousAssistantMessage);
        assertThat(resolution.previousSourceChunks()).containsExactly(sourceChunk);
    }

    @Test
    void resolve_shouldLeaveStandaloneQuestionUnchanged() {
        ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
        RagFollowUpResolverService resolverService = new RagFollowUpResolverService(chatMessageRepository);

        ChatSession session = ChatSession.builder().build();
        session.setId(5L);
        ChatMessage currentUserMessage = ChatMessage.builder()
                .session(session)
                .role(MessageRole.USER)
                .content("Đổi mới là gì?")
                .build();
        currentUserMessage.setId(30L);

        RagFollowUpResolverService.FollowUpResolution resolution =
                resolverService.resolve(session, currentUserMessage, "Đổi mới là gì?");

        assertThat(resolution.followUp()).isFalse();
        assertThat(resolution.originalQuestion()).isEqualTo("Đổi mới là gì?");
        assertThat(resolution.effectiveQuestion()).isEqualTo("Đổi mới là gì?");
        verifyNoInteractions(chatMessageRepository);
    }
}
