package com.example.teacherassistantai.service;

import com.example.teacherassistantai.dto.auth.RegistrationRequest;
import com.example.teacherassistantai.entity.Role;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.integration.email.EmailService;
import com.example.teacherassistantai.integration.email.EmailTemplateName;
import com.example.teacherassistantai.integration.redis.RedisTokenService;
import com.example.teacherassistantai.integration.redis.verification_code.VerificationCodeRepository;
import com.example.teacherassistantai.repository.RoleRepository;
import com.example.teacherassistantai.repository.UserRepository;
import com.example.teacherassistantai.security.JwtService;
import com.example.teacherassistantai.security.UserDetailServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceRegisterTest {

    @Mock
    private RoleRepository roleRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserDetailServiceImpl userDetailService;
    @Mock
    private VerificationCodeRepository verificationCodeRepository;
    @Mock
    private EmailService emailService;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private JwtService jwtService;
    @Mock
    private RedisTokenService redisTokenService;
    @Mock
    private PasswordEncoder passwordEncoder;

    private AuthenticationService authenticationService;

    @BeforeEach
    void setUp() {
        authenticationService = new AuthenticationService(
                roleRepository,
                userRepository,
                userDetailService,
                verificationCodeRepository,
                emailService,
                authenticationManager,
                jwtService,
                redisTokenService,
                passwordEncoder
        );
        ReflectionTestUtils.setField(authenticationService, "activationUrl", "http://localhost/activate");
        ReflectionTestUtils.setField(authenticationService, "verificationCodeLength", 6);
        ReflectionTestUtils.setField(authenticationService, "verificationCodeExpirationMinutes", 15);
    }

    @Test
    void register_shouldSaveRawPasswordForUserPrePersistEncoding() {
        RegistrationRequest request = new RegistrationRequest();
        request.setEmail("student@mail.com");
        request.setPassword("secret123");
        request.setFullName("Student");

        when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(roleRepository.findByName("STUDENT")).thenReturn(Optional.of(Role.builder().name("STUDENT").build()));

        authenticationService.register(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertEquals("secret123", userCaptor.getValue().getPassword());
        verify(passwordEncoder, never()).encode(anyString());
        verify(emailService).sendEmail(
                eq("student@mail.com"),
                eq("Student"),
                eq(EmailTemplateName.ACTIVATE_ACCOUNT),
                eq("http://localhost/activate"),
                anyString(),
                eq("Activate your account")
        );
    }
}
