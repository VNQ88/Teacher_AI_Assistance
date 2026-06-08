package com.example.teacherassistantai.service;

import com.example.teacherassistantai.config.DocumentIngestionProps;
import com.example.teacherassistantai.dto.response.DocumentResponse;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.Role;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.integration.minio.MinioChannel;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import com.example.teacherassistantai.repository.SubjectRepository;
import com.example.teacherassistantai.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceUploadTest {

    private static final String SUBJECT_DOCUMENT_EXISTS_MESSAGE =
            "Môn học này đã có tài liệu. Vui lòng xóa tài liệu hiện tại trước khi tải lên tài liệu mới.";

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private SubjectRepository subjectRepository;
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
    void uploadDocument_shouldUploadAndTriggerProcessing_whenSubjectHasNoDocument() throws Exception {
        Long subjectId = 100L;
        Subject subject = subject(subjectId, "History");
        User uploader = user(2L, "teacher@mail.com", "TEACHER");
        MockMultipartFile file = file("lecture.pdf");

        authenticateAs("teacher@mail.com");
        when(subjectRepository.findById(subjectId)).thenReturn(Optional.of(subject));
        when(documentRepository.existsBySubjectId(subjectId)).thenReturn(false);
        when(userRepository.findByEmail("teacher@mail.com")).thenReturn(Optional.of(uploader));
        when(documentRepository.saveAndFlush(any(Document.class))).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            document.setId(20L);
            return document;
        });

        DocumentResponse response = documentService.uploadDocument(file, subjectId, "Custom title", "desc");

        assertEquals(20L, response.getId());
        assertEquals("Custom title", response.getTitle());
        assertEquals(subjectId, response.getSubjectId());

        ArgumentCaptor<String> objectKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(minioChannel).upload(any(MockMultipartFile.class), objectKeyCaptor.capture());
        assertTrue(objectKeyCaptor.getValue().startsWith("uploads/subjects/100/original/"));
        assertTrue(objectKeyCaptor.getValue().endsWith("-lecture.pdf"));
        verify(documentProcessingService).processDocumentAsync(20L);
    }

    @Test
    void uploadDocument_shouldRejectBeforeStorageUpload_whenSubjectAlreadyHasDocument() throws Exception {
        Long subjectId = 101L;
        Subject subject = subject(subjectId, "Math");
        MockMultipartFile file = file("math.pdf");

        when(subjectRepository.findById(subjectId)).thenReturn(Optional.of(subject));
        when(documentRepository.existsBySubjectId(subjectId)).thenReturn(true);

        InvalidDataException exception = assertThrows(InvalidDataException.class,
                () -> documentService.uploadDocument(file, subjectId, null, null));
        assertEquals(SUBJECT_DOCUMENT_EXISTS_MESSAGE, exception.getMessage());

        verify(userRepository, never()).findByEmail(any());
        verify(minioChannel, never()).upload(any(MockMultipartFile.class), any(String.class));
        verify(documentRepository, never()).saveAndFlush(any(Document.class));
        verify(documentProcessingService, never()).processDocumentAsync(any());
    }

    @Test
    void uploadDocument_shouldRemoveUploadedObjectAndThrowConflict_whenSaveAndFlushFails() throws Exception {
        Long subjectId = 102L;
        Subject subject = subject(subjectId, "Literature");
        User uploader = user(3L, "teacher@mail.com", "TEACHER");
        MockMultipartFile file = file("literature.pdf");

        authenticateAs("teacher@mail.com");
        when(subjectRepository.findById(subjectId)).thenReturn(Optional.of(subject));
        when(documentRepository.existsBySubjectId(subjectId)).thenReturn(false);
        when(userRepository.findByEmail("teacher@mail.com")).thenReturn(Optional.of(uploader));
        when(documentRepository.saveAndFlush(any(Document.class)))
                .thenThrow(new DataIntegrityViolationException("uk_documents_subject_id"));

        InvalidDataException exception = assertThrows(InvalidDataException.class,
                () -> documentService.uploadDocument(file, subjectId, null, null));
        assertEquals(SUBJECT_DOCUMENT_EXISTS_MESSAGE, exception.getMessage());

        ArgumentCaptor<String> objectKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(minioChannel).upload(any(MockMultipartFile.class), objectKeyCaptor.capture());
        verify(minioChannel).removeObject(objectKeyCaptor.getValue());
        verify(documentProcessingService, never()).processDocumentAsync(any());
    }

    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, "n/a", AuthorityUtils.NO_AUTHORITIES)
        );
    }

    private MockMultipartFile file(String originalFilename) {
        return new MockMultipartFile(
                "file",
                originalFilename,
                "application/pdf",
                "content".getBytes()
        );
    }

    private Subject subject(Long id, String name) {
        Subject subject = Subject.builder()
                .name(name)
                .code(name.toUpperCase())
                .active(true)
                .build();
        subject.setId(id);
        return subject;
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
}
