package com.example.teacherassistantai.service;

import com.example.teacherassistantai.config.DocumentIngestionProps;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.Role;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.exception.AccessDeniedOperationException;
import com.example.teacherassistantai.exception.StorageOperationException;
import com.example.teacherassistantai.repository.ClassroomRepository;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import com.example.teacherassistantai.repository.SubjectRepository;
import com.example.teacherassistantai.repository.UserRepository;
import com.example.teacherassistantai.integration.minio.MinioChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceDeleteTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private SubjectRepository subjectRepository;
    @Mock
    private ClassroomRepository classroomRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private DocumentChunkRepository documentChunkRepository;
    @Mock
    private MinioChannel minioChannel;
    @Mock
    private DocumentProcessingService documentProcessingService;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(
                documentRepository,
                subjectRepository,
                classroomRepository,
                userRepository,
                documentChunkRepository,
                minioChannel,
                documentProcessingService,
                new DocumentIngestionProps()
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deleteDocument_shouldDeleteChunksAndDocument_whenCurrentUserIsAdmin() {
        Long documentId = 10L;
        User uploader = user(2L, "uploader@mail.com", "TEACHER");
        Document document = document(documentId, uploader, "uploads/original.pdf", "uploads/markdown.md");

        authenticateAs("admin@mail.com");
        when(userRepository.findByEmail("admin@mail.com")).thenReturn(Optional.of(user(1L, "admin@mail.com", "ADMIN")));
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        documentService.deleteDocument(documentId);

        verify(minioChannel).removeObject("uploads/original.pdf");
        verify(minioChannel).removeObject("uploads/markdown.md");
        InOrder cleanupOrder = inOrder(documentChunkRepository);
        cleanupOrder.verify(documentChunkRepository).deleteMessageSourceLinksByDocumentId(documentId);
        cleanupOrder.verify(documentChunkRepository).deleteByDocumentId(documentId);
        verify(documentRepository).delete(document);
    }

    @Test
    void deleteDocument_shouldThrowAccessDenied_whenCurrentUserIsNotAdminOrUploader() {
        Long documentId = 11L;
        User uploader = user(2L, "uploader@mail.com", "TEACHER");
        Document document = document(documentId, uploader, "uploads/original.pdf", null);

        authenticateAs("other@mail.com");
        when(userRepository.findByEmail("other@mail.com")).thenReturn(Optional.of(user(3L, "other@mail.com", "TEACHER")));
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        assertThrows(AccessDeniedOperationException.class, () -> documentService.deleteDocument(documentId));

        verify(minioChannel, never()).removeObject("uploads/original.pdf");
        verify(documentChunkRepository, never()).deleteMessageSourceLinksByDocumentId(documentId);
        verify(documentChunkRepository, never()).deleteByDocumentId(documentId);
        verify(documentRepository, never()).delete(document);
    }

    @Test
    void deleteDocument_shouldFailFast_whenStorageRemovalFails() {
        Long documentId = 12L;
        User uploader = user(2L, "uploader@mail.com", "TEACHER");
        Document document = document(documentId, uploader, "uploads/original.pdf", "uploads/markdown.md");

        authenticateAs("admin@mail.com");
        when(userRepository.findByEmail("admin@mail.com")).thenReturn(Optional.of(user(1L, "admin@mail.com", "ADMIN")));
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        doThrow(new StorageOperationException("Could not remove object from MinIO"))
                .when(minioChannel).removeObject("uploads/original.pdf");

        assertThrows(StorageOperationException.class, () -> documentService.deleteDocument(documentId));

        verify(documentChunkRepository, never()).deleteMessageSourceLinksByDocumentId(documentId);
        verify(documentChunkRepository, never()).deleteByDocumentId(documentId);
        verify(documentRepository, never()).delete(document);
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
                .enabled(true)
                .password("x")
                .roles(Set.of(Role.builder().name(roleName).build()))
                .build();
        user.setId(id);
        return user;
    }

    private Document document(Long id, User uploader, String originalKey, String markdownKey) {
        Document document = Document.builder()
                .uploadedBy(uploader)
                .originalObjectKey(originalKey)
                .markdownObjectKey(markdownKey)
                .build();
        document.setId(id);
        return document;
    }
}

