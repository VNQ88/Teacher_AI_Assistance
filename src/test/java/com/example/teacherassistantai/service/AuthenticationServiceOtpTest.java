package com.example.teacherassistantai.service;

import com.example.teacherassistantai.dto.auth.SetNewPasswordRequest;
import com.example.teacherassistantai.dto.auth.VerifyCodeRequest;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.integration.email.EmailService;
import com.example.teacherassistantai.integration.redis.RedisTokenService;
import com.example.teacherassistantai.integration.redis.verification_code.VerificationCode;
import com.example.teacherassistantai.integration.redis.verification_code.VerificationCodePurpose;
import com.example.teacherassistantai.integration.redis.verification_code.VerificationCodeRepository;
import com.example.teacherassistantai.repository.RoleRepository;
import com.example.teacherassistantai.repository.UserRepository;
import com.example.teacherassistantai.security.JwtService;
import com.example.teacherassistantai.security.UserDetailServiceImpl;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceOtpTest {

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
    void forgotPassword_shouldDeleteExistingResetCodeBeforeSavingNewCode() throws MessagingException {
        User user = user(7L, "student@mail.com", true);
        VerificationCode oldCode = VerificationCode.builder()
                .code("111111")
                .userId(user.getId())
                .purpose(VerificationCodePurpose.RESET_PASSWORD)
                .build();
        List<VerificationCode> oldCodes = List.of(oldCode);

        when(userRepository.existsByEmail(user.getEmail())).thenReturn(true);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(verificationCodeRepository.findAllByUserIdAndPurpose(user.getId(), VerificationCodePurpose.RESET_PASSWORD))
                .thenReturn(oldCodes);

        authenticationService.forgotPassword(user.getEmail());

        verify(verificationCodeRepository).deleteAll(oldCodes);

        ArgumentCaptor<VerificationCode> codeCaptor = ArgumentCaptor.forClass(VerificationCode.class);
        verify(verificationCodeRepository).save(codeCaptor.capture());
        VerificationCode savedCode = codeCaptor.getValue();
        assertEquals(user.getId(), savedCode.getUserId());
        assertEquals(VerificationCodePurpose.RESET_PASSWORD, savedCode.getPurpose());
        assertEquals(15 * 60L, savedCode.getTimeToLive());
    }

    @Test
    void resetPassword_shouldVerifyCodeByPurposeAndCode() {
        User user = user(8L, "student@mail.com", true);
        VerificationCode resetCode = VerificationCode.builder()
                .code("123456")
                .userId(user.getId())
                .purpose(VerificationCodePurpose.RESET_PASSWORD)
                .build();
        SetNewPasswordRequest request = new SetNewPasswordRequest();
        request.setCode(" 123456 ");
        request.setNewPassword("new-password");
        request.setConfirmPassword("new-password");

        when(verificationCodeRepository.findByPurposeAndCode(VerificationCodePurpose.RESET_PASSWORD, "123456"))
                .thenReturn(Optional.of(resetCode));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(request.getNewPassword())).thenReturn("encoded-password");

        authenticationService.resetPassword(request);

        verify(verificationCodeRepository).findByPurposeAndCode(VerificationCodePurpose.RESET_PASSWORD, "123456");
        verify(verificationCodeRepository).delete(resetCode);
        assertEquals("encoded-password", user.getPassword());
    }

    @Test
    void verifyResetCode_shouldThrowBadRequestExceptionWhenCodeIsExpiredOrInvalid() {
        VerifyCodeRequest request = new VerifyCodeRequest();
        request.setCode("123456");

        when(verificationCodeRepository.findByPurposeAndCode(VerificationCodePurpose.RESET_PASSWORD, request.getCode()))
                .thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authenticationService.verifyResetCode(request)
        );
        assertEquals("Code is invalid or has expired", exception.getMessage());
    }

    private User user(Long id, String email, boolean enabled) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setFullName("Student");
        user.setEnabled(enabled);
        return user;
    }
}
