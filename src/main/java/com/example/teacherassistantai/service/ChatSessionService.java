package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.ChatSessionType;
import com.example.teacherassistantai.common.response.PageResponse;
import com.example.teacherassistantai.dto.request.CreateChatSessionRequest;
import com.example.teacherassistantai.dto.response.ChatSessionResponse;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.repository.ChatMessageRepository;
import com.example.teacherassistantai.repository.ChatSessionRepository;
import com.example.teacherassistantai.repository.SubjectRepository;
import com.example.teacherassistantai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private static final String ADMIN_ROLE = "ADMIN";

    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final SubjectRepository subjectRepository;
    private final UserRepository userRepository;

    @Transactional
    public ChatSessionResponse create(CreateChatSessionRequest request) {
        User current = getCurrentUser();
        Subject subject = subjectRepository.findById(request.getSubjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Subject not found with id: " + request.getSubjectId()));

        ChatSession session = ChatSession.builder()
                .user(current)
                .sessionType(ChatSessionType.KNOWLEDGE_QA)
                .subject(subject)
                .title(request.getTitle())
                .active(true)
                .build();

        return toResponse(chatSessionRepository.save(session));
    }

    @Transactional(readOnly = true)
    public PageResponse<?> getMySessions(Long subjectId, int pageNo, int pageSize) {
        User current = getCurrentUser();
        Page<ChatSession> page = chatSessionRepository.findByUserAndSubject(current.getId(), subjectId, PageRequest.of(pageNo, pageSize));
        List<ChatSessionResponse> items = page.getContent().stream().map(this::toResponse).toList();
        return PageResponse.<List<ChatSessionResponse>>builder()
                .pageNo(pageNo)
                .pageSize(pageSize)
                .totalPage(page.getTotalPages())
                .items(items)
                .build();
    }

    @Transactional(readOnly = true)
    public ChatSessionResponse getMySession(Long sessionId) {
        return toResponse(getOwnedSession(sessionId));
    }

    @Transactional
    public void close(Long sessionId) {
        ChatSession session = getOwnedSession(sessionId);
        session.setActive(false);
        chatSessionRepository.save(session);
    }

    @Transactional
    public void delete(Long sessionId) {
        ChatSession session = getOwnedOrAdminSession(sessionId);
        chatMessageRepository.deleteMessageSourceLinksBySessionId(session.getId());
        chatMessageRepository.deleteBySessionId(session.getId());
        chatSessionRepository.delete(session);
    }

    @Transactional(readOnly = true)
    public ChatSession getOwnedSession(Long sessionId) {
        User current = getCurrentUser();
        return chatSessionRepository.findByIdAndUserId(sessionId, current.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Chat session not found with id: " + sessionId));
    }

    private ChatSession getOwnedOrAdminSession(Long sessionId) {
        User current = getCurrentUser();
        boolean isAdmin = current.getRoles().stream()
                .anyMatch(role -> ADMIN_ROLE.equalsIgnoreCase(role.getName()));

        if (isAdmin) {
            return chatSessionRepository.findById(sessionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Chat session not found with id: " + sessionId));
        }

        return chatSessionRepository.findByIdAndUserId(sessionId, current.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Chat session not found with id: " + sessionId));
    }

    private User getCurrentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ResourceNotFoundException("User not authenticated");
        }
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private ChatSessionResponse toResponse(ChatSession session) {
        return ChatSessionResponse.builder()
                .id(session.getId())
                .subjectId(session.getSubject() != null ? session.getSubject().getId() : null)
                .subjectName(session.getSubject() != null ? session.getSubject().getName() : null)
                .title(session.getTitle())
                .active(session.getActive())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .build();
    }
}

