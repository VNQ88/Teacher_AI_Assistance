package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.Role;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.exception.InvalidDataException;
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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubjectServiceDeleteTest {

    @Mock
    private SubjectRepository subjectRepository;
    @Mock
    private UserRepository userRepository;

    private SubjectService subjectService;

    @BeforeEach
    void setUp() {
        subjectService = new SubjectService(subjectRepository, userRepository);
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

        subjectService.deleteSubject(subjectId);

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

        subjectService.deleteSubject(subjectId);

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
