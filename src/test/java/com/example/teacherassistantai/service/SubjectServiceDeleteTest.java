package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.Role;
import com.example.teacherassistantai.entity.QuestionBank;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.repository.ExamQuestionRepository;
import com.example.teacherassistantai.repository.QuestionBankRepository;
import com.example.teacherassistantai.repository.QuestionRepository;
import com.example.teacherassistantai.repository.StudentAnswerRepository;
import com.example.teacherassistantai.repository.SubjectRepository;
import com.example.teacherassistantai.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubjectServiceDeleteTest {

    @Mock
    private SubjectRepository subjectRepository;
    @Mock
    private QuestionBankRepository questionBankRepository;
    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private ExamQuestionRepository examQuestionRepository;
    @Mock
    private StudentAnswerRepository studentAnswerRepository;
    @Mock
    private UserRepository userRepository;

    private SubjectService subjectService;

    @BeforeEach
    void setUp() {
        subjectService = new SubjectService(
                subjectRepository,
                questionBankRepository,
                questionRepository,
                examQuestionRepository,
                studentAnswerRepository,
                userRepository
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
        when(questionBankRepository.findBySubject_Id(subjectId)).thenReturn(List.of());

        subjectService.deleteSubject(subjectId);

        verify(questionRepository, never()).findIdsByQuestionBankId(org.mockito.ArgumentMatchers.anyLong());
        verify(questionRepository, never()).deleteByQuestionBankId(org.mockito.ArgumentMatchers.anyLong());
        verify(examQuestionRepository, never()).findIdsByQuestionIdIn(org.mockito.ArgumentMatchers.anyList());
        verify(examQuestionRepository, never()).deleteByQuestionIdIn(org.mockito.ArgumentMatchers.anyList());
        verify(studentAnswerRepository, never()).deleteByExamQuestionIdIn(org.mockito.ArgumentMatchers.anyList());
        verify(subjectRepository).delete(subject);
    }

    @Test
    void deleteSubject_shouldDelete_whenCurrentUserIsOwner() {
        Long subjectId = 2L;
        User owner = user(5L, "owner@mail.com", "TEACHER");
        Subject subject = subject(subjectId, owner.getId());

        authenticateAs("owner@mail.com");
        when(userRepository.findByEmail("owner@mail.com")).thenReturn(Optional.of(owner));
        when(subjectRepository.findById(subjectId)).thenReturn(Optional.of(subject));
        when(questionBankRepository.findBySubject_Id(subjectId)).thenReturn(List.of());

        subjectService.deleteSubject(subjectId);

        verify(questionRepository, never()).findIdsByQuestionBankId(org.mockito.ArgumentMatchers.anyLong());
        verify(questionRepository, never()).deleteByQuestionBankId(org.mockito.ArgumentMatchers.anyLong());
        verify(examQuestionRepository, never()).findIdsByQuestionIdIn(org.mockito.ArgumentMatchers.anyList());
        verify(examQuestionRepository, never()).deleteByQuestionIdIn(org.mockito.ArgumentMatchers.anyList());
        verify(studentAnswerRepository, never()).deleteByExamQuestionIdIn(org.mockito.ArgumentMatchers.anyList());
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

        assertThrows(InvalidDataException.class, () -> subjectService.deleteSubject(subjectId));

        verify(subjectRepository, never()).delete(subject);
    }

    @Test
    void deleteSubject_shouldDeleteQuestionBanksBeforeDeletingSubject() {
        Long subjectId = 4L;
        User owner = user(10L, "owner2@mail.com", "TEACHER");
        Subject subject = subject(subjectId, owner.getId());
        QuestionBank qb1 = QuestionBank.builder().title("qb1").build();
        QuestionBank qb2 = QuestionBank.builder().title("qb2").build();
        qb1.setId(11L);
        qb2.setId(12L);

        authenticateAs("owner2@mail.com");
        when(userRepository.findByEmail("owner2@mail.com")).thenReturn(Optional.of(owner));
        when(subjectRepository.findById(subjectId)).thenReturn(Optional.of(subject));
        when(questionBankRepository.findBySubject_Id(subjectId)).thenReturn(List.of(qb1, qb2));
        when(questionRepository.findIdsByQuestionBankId(qb1.getId())).thenReturn(List.of(101L, 102L));
        when(questionRepository.findIdsByQuestionBankId(qb2.getId())).thenReturn(List.of(201L));
        when(examQuestionRepository.findIdsByQuestionIdIn(List.of(101L, 102L))).thenReturn(List.of(1001L));
        when(examQuestionRepository.findIdsByQuestionIdIn(List.of(201L))).thenReturn(List.of());

        subjectService.deleteSubject(subjectId);

        verify(questionRepository).findIdsByQuestionBankId(qb1.getId());
        verify(questionRepository).findIdsByQuestionBankId(qb2.getId());
        verify(examQuestionRepository).findIdsByQuestionIdIn(List.of(101L, 102L));
        verify(examQuestionRepository).findIdsByQuestionIdIn(List.of(201L));
        verify(studentAnswerRepository).deleteByExamQuestionIdIn(List.of(1001L));
        verify(examQuestionRepository).deleteByQuestionIdIn(List.of(101L, 102L));
        verify(examQuestionRepository).deleteByQuestionIdIn(List.of(201L));
        verify(questionRepository).deleteByQuestionBankId(qb1.getId());
        verify(questionRepository).deleteByQuestionBankId(qb2.getId());
        verify(questionBankRepository).deleteAll(List.of(qb1, qb2));
        verify(subjectRepository).delete(subject);
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
}


