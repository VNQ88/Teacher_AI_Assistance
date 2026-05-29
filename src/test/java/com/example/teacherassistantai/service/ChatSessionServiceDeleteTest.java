package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.Role;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.repository.ChatMessageRepository;
import com.example.teacherassistantai.repository.ChatSessionRepository;
import com.example.teacherassistantai.repository.SubjectRepository;
import com.example.teacherassistantai.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatSessionServiceDeleteTest {

    @Mock
    private ChatMessageRepository chatMessageRepository;
    @Mock
    private ChatSessionRepository chatSessionRepository;
    @Mock
    private SubjectRepository subjectRepository;
    @Mock
    private UserRepository userRepository;

    private ChatSessionService chatSessionService;

    @BeforeEach
    void setUp() {
        chatSessionService = new ChatSessionService(
                chatMessageRepository,
                chatSessionRepository,
                subjectRepository,
                userRepository
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void delete_shouldDeleteSessionAndMessages_whenOwnerDeletes() {
        Long sessionId = 100L;
        User owner = user(1L, "owner@mail.com", "TEACHER");
        ChatSession session = chatSession(sessionId, owner);

        authenticateAs("owner@mail.com");
        when(userRepository.findByEmail("owner@mail.com")).thenReturn(Optional.of(owner));
        when(chatSessionRepository.findByIdAndUserId(sessionId, owner.getId())).thenReturn(Optional.of(session));

        chatSessionService.delete(sessionId);

        InOrder deletionOrder = inOrder(chatMessageRepository, chatSessionRepository);
        deletionOrder.verify(chatMessageRepository).deleteMessageSourceLinksBySessionId(sessionId);
        deletionOrder.verify(chatMessageRepository).deleteBySessionId(sessionId);
        deletionOrder.verify(chatSessionRepository).delete(session);
    }

    @Test
    void delete_shouldAllowAdminToDeleteAnySession() {
        Long sessionId = 101L;
        User admin = user(9L, "admin@mail.com", "ADMIN");
        User owner = user(2L, "owner@mail.com", "STUDENT");
        ChatSession session = chatSession(sessionId, owner);

        authenticateAs("admin@mail.com");
        when(userRepository.findByEmail("admin@mail.com")).thenReturn(Optional.of(admin));
        when(chatSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        chatSessionService.delete(sessionId);

        verify(chatSessionRepository, never()).findByIdAndUserId(sessionId, admin.getId());
        verify(chatMessageRepository).deleteMessageSourceLinksBySessionId(sessionId);
        verify(chatMessageRepository).deleteBySessionId(sessionId);
        verify(chatSessionRepository).delete(session);
    }

    @Test
    void delete_shouldThrowNotFound_whenNonOwnerDeletesOthersSession() {
        Long sessionId = 102L;
        User requester = user(3L, "requester@mail.com", "TEACHER");

        authenticateAs("requester@mail.com");
        when(userRepository.findByEmail("requester@mail.com")).thenReturn(Optional.of(requester));
        when(chatSessionRepository.findByIdAndUserId(sessionId, requester.getId())).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> chatSessionService.delete(sessionId));

        verify(chatMessageRepository, never()).deleteMessageSourceLinksBySessionId(sessionId);
        verify(chatMessageRepository, never()).deleteBySessionId(sessionId);
        verify(chatSessionRepository, never()).delete(org.mockito.ArgumentMatchers.any(ChatSession.class));
    }

    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, "n/a", AuthorityUtils.NO_AUTHORITIES)
        );
    }

    private User user(Long id, String email, String roleName) {
        User user = User.builder()
                .email(email)
                .fullName(email)
                .password("x")
                .enabled(true)
                .roles(Set.of(Role.builder().name(roleName).build()))
                .build();
        user.setId(id);
        return user;
    }

    private ChatSession chatSession(Long id, User owner) {
        ChatSession session = ChatSession.builder()
                .user(owner)
                .active(true)
                .build();
        session.setId(id);
        return session;
    }
}

