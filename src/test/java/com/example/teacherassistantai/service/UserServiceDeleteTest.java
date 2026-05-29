package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.Role;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.integration.minio.MinioChannel;
import com.example.teacherassistantai.mapper.UserMapper;
import com.example.teacherassistantai.repository.AgentLogRepository;
import com.example.teacherassistantai.repository.ChatMessageRepository;
import com.example.teacherassistantai.repository.ChatSessionRepository;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import com.example.teacherassistantai.repository.DocumentRepository.DocumentStorageObject;
import com.example.teacherassistantai.repository.RoleRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceDeleteTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private RoleRepository roleRepository;
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
    private SubjectRepository subjectRepository;
    @Mock
    private AgentLogRepository agentLogRepository;
    @Mock
    private MinioChannel minioChannel;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(
                userRepository,
                userMapper,
                passwordEncoder,
                roleRepository,
                documentRepository,
                documentChunkRepository,
                documentNodeRepository,
                documentNodeArtifactRepository,
                chatMessageRepository,
                chatSessionRepository,
                subjectRepository,
                agentLogRepository,
                minioChannel
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deleteUser_shouldDeleteRelatedData_whenDeletingAnotherUser() {
        long userId = 2L;
        User target = user(userId, "target@mail.com", "TEACHER");

        authenticateAs("admin@mail.com");
        when(userRepository.findById(userId)).thenReturn(Optional.of(target));
        when(documentRepository.findStorageObjectsForUserDeletion(userId)).thenReturn(List.of(
                storageObject(10L, "uploads/original.pdf", "uploads/markdown.md", "uploads/hierarchy.json", "uploads/chunks.json")
        ));

        userService.deleteUser(userId);

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
                subjectRepository,
                userRepository
        );
        deletionOrder.verify(agentLogRepository).deleteByUserId(userId);
        deletionOrder.verify(chatMessageRepository).deleteMessageSourceLinksByUserId(userId);
        deletionOrder.verify(chatMessageRepository).deleteByUserId(userId);
        deletionOrder.verify(chatSessionRepository).deleteByUserId(userId);
        deletionOrder.verify(documentChunkRepository).deleteMessageSourceLinksByUserId(userId);
        deletionOrder.verify(documentNodeArtifactRepository).deleteByUserId(userId);
        deletionOrder.verify(documentChunkRepository).deleteByUserId(userId);
        deletionOrder.verify(documentNodeRepository).deleteByUserId(userId);
        deletionOrder.verify(documentRepository).deleteByUserId(userId);
        deletionOrder.verify(subjectRepository).deleteByOwnerId(userId);
        deletionOrder.verify(userRepository).deleteRoleLinksByUserId(userId);
        deletionOrder.verify(userRepository).delete(target);
    }

    @Test
    void deleteUser_shouldThrow_whenDeletingCurrentUser() {
        long userId = 3L;
        User currentUser = user(userId, "current@mail.com", "ADMIN");

        authenticateAs(currentUser.getEmail());
        when(userRepository.findById(userId)).thenReturn(Optional.of(currentUser));

        assertThrows(InvalidDataException.class, () -> userService.deleteUser(userId));

        verify(documentRepository, never()).findStorageObjectsForUserDeletion(userId);
        verify(agentLogRepository, never()).deleteByUserId(userId);
        verify(chatMessageRepository, never()).deleteMessageSourceLinksByUserId(userId);
        verify(chatMessageRepository, never()).deleteByUserId(userId);
        verify(chatSessionRepository, never()).deleteByUserId(userId);
        verify(documentChunkRepository, never()).deleteMessageSourceLinksByUserId(userId);
        verify(documentNodeArtifactRepository, never()).deleteByUserId(userId);
        verify(documentChunkRepository, never()).deleteByUserId(userId);
        verify(documentNodeRepository, never()).deleteByUserId(userId);
        verify(documentRepository, never()).deleteByUserId(userId);
        verify(subjectRepository, never()).deleteByOwnerId(userId);
        verify(userRepository, never()).deleteRoleLinksByUserId(userId);
        verify(userRepository, never()).delete(currentUser);
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
