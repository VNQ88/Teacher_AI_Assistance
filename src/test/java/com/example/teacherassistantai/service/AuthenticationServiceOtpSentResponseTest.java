package com.example.teacherassistantai.service;

import com.example.teacherassistantai.dto.auth.OtpSentResponse;
import com.example.teacherassistantai.dto.auth.RegistrationRequest;
import com.example.teacherassistantai.entity.Role;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.integration.email.EmailService;
import com.example.teacherassistantai.integration.email.EmailTemplateName;
import com.example.teacherassistantai.integration.redis.RedisTokenService;
import com.example.teacherassistantai.integration.redis.verification_code.VerificationCode;
import com.example.teacherassistantai.integration.redis.verification_code.VerificationCodePurpose;
import com.example.teacherassistantai.integration.redis.verification_code.VerificationCodeRepository;
import com.example.teacherassistantai.repository.RoleRepository;
import com.example.teacherassistantai.repository.UserRepository;
import com.example.teacherassistantai.security.JwtService;
import com.example.teacherassistantai.security.UserDetailServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceOtpSentResponseTest {

    @Mock private RoleRepository roleRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserDetailServiceImpl userDetailService;
    @Mock private VerificationCodeRepository verificationCodeRepository;
    @Mock private EmailService emailService;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtService jwtService;
    @Mock private RedisTokenService redisTokenService;
    @Mock private PasswordEncoder passwordEncoder;

    private AuthenticationService authenticationService;

    private static final int EXPIRY_MINUTES = 15;
    private static final int EXPECTED_SECONDS = EXPIRY_MINUTES * 60;

    @BeforeEach
    void setUp() {
        authenticationService = new AuthenticationService(
                roleRepository, userRepository, userDetailService,
                verificationCodeRepository, emailService, authenticationManager,
                jwtService, redisTokenService, passwordEncoder
        );
        ReflectionTestUtils.setField(authenticationService, "activationUrl", "http://localhost/activate");
        ReflectionTestUtils.setField(authenticationService, "verificationCodeLength", 6);
        ReflectionTestUtils.setField(authenticationService, "verificationCodeExpirationMinutes", EXPIRY_MINUTES);
    }

    // ── register ────────────────────────────────────────────────────────────

    @Test
    void register_newEmail_returnsOtpSentWithResentFalse() {
        RegistrationRequest request = registrationRequest("new@mail.com");
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(roleRepository.findByName("STUDENT")).thenReturn(Optional.of(Role.builder().name("STUDENT").build()));

        OtpSentResponse result = authenticationService.register(request);

        assertFalse(result.isResent());
        assertEquals(EXPECTED_SECONDS, result.getExpiresInSeconds());
    }

    @Test
    void register_existingUnactivatedEmail_returnsOtpSentWithResentTrue() {
        User unactivated = user(1L, "exists@mail.com", false);
        when(userRepository.existsByEmail(unactivated.getEmail())).thenReturn(true);
        when(userDetailService.loadUserByUsername(unactivated.getEmail())).thenReturn(unactivated);

        OtpSentResponse result = authenticationService.register(registrationRequest(unactivated.getEmail()));

        assertTrue(result.isResent());
        assertEquals(EXPECTED_SECONDS, result.getExpiresInSeconds());
    }

    @Test
    void register_existingActivatedEmail_throwsException() {
        User activated = user(2L, "active@mail.com", true);
        when(userRepository.existsByEmail(activated.getEmail())).thenReturn(true);
        when(userDetailService.loadUserByUsername(activated.getEmail())).thenReturn(activated);

        assertThrows(RuntimeException.class,
                () -> authenticationService.register(registrationRequest(activated.getEmail())));
    }

    // ── resendActivationCode ─────────────────────────────────────────────────

    @Test
    void resendActivationCode_returnsOtpSentWithCorrectExpiry() {
        User user = user(3L, "pending@mail.com", false);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        OtpSentResponse result = authenticationService.resendActivationCode(user.getEmail());

        assertEquals(EXPECTED_SECONDS, result.getExpiresInSeconds());
    }

    @Test
    void resendActivationCode_alreadyActivated_throwsException() {
        User activated = user(4L, "active@mail.com", true);
        when(userRepository.findByEmail(activated.getEmail())).thenReturn(Optional.of(activated));

        assertThrows(RuntimeException.class,
                () -> authenticationService.resendActivationCode(activated.getEmail()));
    }

    // ── forgotPassword ───────────────────────────────────────────────────────

    @Test
    void forgotPassword_activatedUser_sendsResetEmailAndReturnsOtpSent() {
        User user = user(5L, "active@mail.com", true);
        when(userRepository.existsByEmail(user.getEmail())).thenReturn(true);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        OtpSentResponse result = authenticationService.forgotPassword(user.getEmail());

        assertEquals(EXPECTED_SECONDS, result.getExpiresInSeconds());
        verify(emailService).sendEmail(
                eq(user.getEmail()), anyString(),
                eq(EmailTemplateName.RESET_PASSWORD), anyString(), anyString(), anyString());
    }

    @Test
    void forgotPassword_unactivatedUser_sendsActivationEmailAndReturnsOtpSent() {
        User user = user(6L, "pending@mail.com", false);
        when(userRepository.existsByEmail(user.getEmail())).thenReturn(true);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        OtpSentResponse result = authenticationService.forgotPassword(user.getEmail());

        assertEquals(EXPECTED_SECONDS, result.getExpiresInSeconds());
        verify(emailService).sendEmail(
                eq(user.getEmail()), anyString(),
                eq(EmailTemplateName.ACTIVATE_ACCOUNT), anyString(), anyString(), anyString());
    }

    @Test
    void forgotPassword_unknownEmail_throwsException() {
        when(userRepository.existsByEmail("ghost@mail.com")).thenReturn(false);

        assertThrows(RuntimeException.class,
                () -> authenticationService.forgotPassword("ghost@mail.com"));
    }

    // ── resendResetCode (new endpoint) ───────────────────────────────────────

    @Test
    void resendResetCode_activatedUser_sendsResetEmailAndReturnsOtpSent() {
        User user = user(7L, "active@mail.com", true);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(verificationCodeRepository.findAllByUserIdAndPurpose(user.getId(), VerificationCodePurpose.RESET_PASSWORD))
                .thenReturn(List.of());

        OtpSentResponse result = authenticationService.resendResetCode(user.getEmail());

        assertEquals(EXPECTED_SECONDS, result.getExpiresInSeconds());
        verify(emailService).sendEmail(
                eq(user.getEmail()), anyString(),
                eq(EmailTemplateName.RESET_PASSWORD), anyString(), anyString(), anyString());
    }

    @Test
    void resendResetCode_deletesOldCodeBeforeSavingNew() {
        User user = user(8L, "active@mail.com", true);
        VerificationCode oldCode = VerificationCode.builder()
                .code("999999").userId(user.getId())
                .purpose(VerificationCodePurpose.RESET_PASSWORD).build();

        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(verificationCodeRepository.findAllByUserIdAndPurpose(user.getId(), VerificationCodePurpose.RESET_PASSWORD))
                .thenReturn(List.of(oldCode));

        authenticationService.resendResetCode(user.getEmail());

        verify(verificationCodeRepository).deleteAll(List.of(oldCode));
        verify(verificationCodeRepository).save(any(VerificationCode.class));
    }

    @Test
    void resendResetCode_unactivatedUser_throwsException() {
        User user = user(9L, "pending@mail.com", false);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        assertThrows(RuntimeException.class,
                () -> authenticationService.resendResetCode(user.getEmail()));
    }

    @Test
    void resendResetCode_unknownEmail_throwsException() {
        when(userRepository.findByEmail("ghost@mail.com")).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class,
                () -> authenticationService.resendResetCode("ghost@mail.com"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private User user(Long id, String email, boolean enabled) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setFullName("Test User");
        user.setEnabled(enabled);
        return user;
    }

    private RegistrationRequest registrationRequest(String email) {
        RegistrationRequest request = new RegistrationRequest();
        request.setEmail(email);
        request.setPassword("secret123");
        request.setFullName("Test User");
        return request;
    }
}
