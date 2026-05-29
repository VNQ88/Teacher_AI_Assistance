package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.Role;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.exception.AccessDeniedOperationException;
import com.example.teacherassistantai.integration.minio.MinioChannel;
import com.example.teacherassistantai.repository.AgentLogRepository;
import com.example.teacherassistantai.repository.ChatMessageRepository;
import com.example.teacherassistantai.repository.ChatSessionRepository;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import com.example.teacherassistantai.repository.DocumentRepository.DocumentStorageObject;
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

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubjectServiceDeleteTest {

    @Mock
    private SubjectRepository subjectRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DocumentChunkRepository documentChunkRepository;
    @Mock
    private DocumentNodeRepository documentNodeRepository;
    @Mock
    private DocumentNodeArtifactRepository documentNodeArtifactRepository;
    @Mock
    private ChatMessageRepository chatMessageRepository;
    @Mock
    private ChatSessionRepository chatSessionRepository;
    @Mock
    private AgentLogRepository agentLogRepository;
    @Mock
    private MinioChannel minioChannel;

    private SubjectService subjectService;

    @BeforeEach
    void setUp() {
        subjectService = new SubjectService(
                subjectRepository,
                userRepository,
                documentRepository,
                documentChunkRepository,
                documentNodeRepository,
                documentNodeArtifactRepository,
                chatMessageRepository,
                chatSessionRepository,
                agentLogRepository,
                minioChannel
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deleteSubject_shouldDelete_whenCurrentUserIsAdmin() {
        Long subjectId = 1L;
        User owner = user(2L, "owner@mail.com", "TEACHER");
        Subject subject = subject(subjectId, owner.getId());

        authenticateAs("admin@mail.com");
        when(userRepository.findByEmail("admin@mail.com")).thenReturn(Optional.of(user(9L, "admin@mail.com", "ADMIN")));
        when(subjectRepository.findById(subjectId)).thenReturn(Optional.of(subject));
        when(documentRepository.findStorageObjectsBySubjectId(subjectId)).thenReturn(List.of(
                storageObject(10L, "uploads/original.pdf", "uploads/markdown.md", "uploads/hierarchy.json", "uploads/chunks.json")
        ));

        subjectService.deleteSubject(subjectId);

        verify(minioChannel).removeObject("uploads/original.pdf");
        verify(minioChannel).removeObject("uploads/markdown.md");
        verify(minioChannel).removeObject("uploads/hierarchy.json");
        verify(minioChannel).removeObject("uploads/chunks.json");

        InOrder deletionOrder = inOrder(
                agentLogRepository,
                chatMessageRepository,
                chatSessionRepository,
                documentChunkRepository,
                documentNodeArtifactRepository,
                documentNodeRepository,
                documentRepository,
                subjectRepository
        );
        deletionOrder.verify(agentLogRepository).deleteBySubjectId(subjectId);
        deletionOrder.verify(chatMessageRepository).deleteMessageSourceLinksBySubjectId(subjectId);
        deletionOrder.verify(chatMessageRepository).deleteBySubjectId(subjectId);
        deletionOrder.verify(chatSessionRepository).deleteBySubjectId(subjectId);
        deletionOrder.verify(documentChunkRepository).deleteMessageSourceLinksBySubjectId(subjectId);
        deletionOrder.verify(documentNodeArtifactRepository).deleteBySubjectId(subjectId);
        deletionOrder.verify(documentChunkRepository).deleteBySubjectId(subjectId);
        deletionOrder.verify(documentNodeRepository).deleteBySubjectId(subjectId);
        deletionOrder.verify(documentRepository).deleteBySubjectId(subjectId);
        deletionOrder.verify(subjectRepository).delete(subject);
    }

    @Test
    void deleteSubject_shouldDelete_whenCurrentUserIsOwner() {
        Long subjectId = 2L;
        User owner = user(5L, "owner@mail.com", "TEACHER");
        Subject subject = subject(subjectId, owner.getId());

        authenticateAs("owner@mail.com");
        when(userRepository.findByEmail("owner@mail.com")).thenReturn(Optional.of(owner));
        when(subjectRepository.findById(subjectId)).thenReturn(Optional.of(subject));
        when(documentRepository.findStorageObjectsBySubjectId(subjectId)).thenReturn(List.of());

        subjectService.deleteSubject(subjectId);

        verify(chatMessageRepository).deleteMessageSourceLinksBySubjectId(subjectId);
        verify(chatMessageRepository).deleteBySubjectId(subjectId);
        verify(chatSessionRepository).deleteBySubjectId(subjectId);
        verify(documentChunkRepository).deleteMessageSourceLinksBySubjectId(subjectId);
        verify(documentNodeArtifactRepository).deleteBySubjectId(subjectId);
        verify(documentChunkRepository).deleteBySubjectId(subjectId);
        verify(documentNodeRepository).deleteBySubjectId(subjectId);
        verify(documentRepository).deleteBySubjectId(subjectId);
        verify(agentLogRepository).deleteBySubjectId(subjectId);
        verify(subjectRepository).delete(subject);
    }

    @Test
    void deleteSubject_shouldThrow_whenCurrentUserIsNotAdminOrOwner() {
        Long subjectId = 3L;
        User owner = user(7L, "owner@mail.com", "TEACHER");
        Subject subject = subject(subjectId, owner.getId());

        authenticateAs("other@mail.com");
        when(userRepository.findByEmail("other@mail.com")).thenReturn(Optional.of(user(8L, "other@mail.com", "TEACHER")));
        when(subjectRepository.findById(subjectId)).thenReturn(Optional.of(subject));

        assertThrows(AccessDeniedOperationException.class, () -> subjectService.deleteSubject(subjectId));

        verify(documentRepository, never()).findStorageObjectsBySubjectId(subjectId);
        verify(chatMessageRepository, never()).deleteMessageSourceLinksBySubjectId(subjectId);
        verify(chatMessageRepository, never()).deleteBySubjectId(subjectId);
        verify(chatSessionRepository, never()).deleteBySubjectId(subjectId);
        verify(documentChunkRepository, never()).deleteMessageSourceLinksBySubjectId(subjectId);
        verify(documentNodeArtifactRepository, never()).deleteBySubjectId(subjectId);
        verify(documentChunkRepository, never()).deleteBySubjectId(subjectId);
        verify(documentNodeRepository, never()).deleteBySubjectId(subjectId);
        verify(documentRepository, never()).deleteBySubjectId(subjectId);
        verify(agentLogRepository, never()).deleteBySubjectId(subjectId);
        verify(subjectRepository, never()).delete(subject);
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

    private Subject subject(Long id, Long ownerId) {
        Subject subject = Subject.builder()
                .name("Math")
                .code("MATH")
                .active(true)
                .ownerId(ownerId)
                .build();
        subject.setId(id);
        return subject;
    }

    private DocumentStorageObject storageObject(Long id,
                                                String originalKey,
                                                String markdownKey,
                                                String hierarchyKey,
                                                String chunksKey) {
        return new DocumentStorageObject() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public String getOriginalObjectKey() {
                return originalKey;
            }

            @Override
            public String getMarkdownObjectKey() {
                return markdownKey;
            }

            @Override
            public String getHierarchyObjectKey() {
                return hierarchyKey;
            }

            @Override
            public String getChunksObjectKey() {
                return chunksKey;
            }
        };
    }
}
