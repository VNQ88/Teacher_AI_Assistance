package com.example.teacherassistantai.service;

import com.example.teacherassistantai.dto.auth.AuthenticationResponse;
import com.example.teacherassistantai.dto.auth.SetNewPasswordRequest;
import com.example.teacherassistantai.dto.auth.VerifyCodeRequest;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.exception.UnauthorizedException;
import com.example.teacherassistantai.integration.email.EmailService;
import com.example.teacherassistantai.integration.email.EmailTemplateName;
import com.example.teacherassistantai.integration.redis.RedisTokenService;
import com.example.teacherassistantai.integration.redis.verification_code.VerificationCode;
import com.example.teacherassistantai.integration.redis.verification_code.VerificationCodePurpose;
import com.example.teacherassistantai.integration.redis.verification_code.VerificationCodeRepository;
import com.example.teacherassistantai.repository.RoleRepository;
import com.example.teacherassistantai.repository.UserRepository;
import com.example.teacherassistantai.security.JwtService;
import com.example.teacherassistantai.security.TokenType;
import com.example.teacherassistantai.security.UserDetailServiceImpl;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
        ReflectionTestUtils.setField(authenticationService, "validDuration", 600);
        ReflectionTestUtils.setField(authenticationService, "refreshDuration", 360000);
    }

    @Test
    void forgotPassword_shouldDeleteExistingResetCodeBeforeSavingNewCode() {
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
        verify(emailService).sendEmail(
                eq("student@mail.com"),
                eq("Student"),
                eq(EmailTemplateName.RESET_PASSWORD),
                eq("http://localhost/activate"),
                anyString(),
                eq("Reset your password")
        );
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
        request.setEmail(user.getEmail());
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
        verify(redisTokenService).revokeAllUserTokens(user.getId());
        assertEquals("encoded-password", user.getPassword());
    }

    @Test
    void resetPassword_shouldRejectCodeWhenEmailDoesNotMatchCodeOwner() {
        User user = user(8L, "student@mail.com", true);
        VerificationCode resetCode = VerificationCode.builder()
                .code("123456")
                .userId(user.getId())
                .purpose(VerificationCodePurpose.RESET_PASSWORD)
                .build();
        SetNewPasswordRequest request = new SetNewPasswordRequest();
        request.setEmail("other@mail.com");
        request.setCode("123456");
        request.setNewPassword("new-password");
        request.setConfirmPassword("new-password");

        when(verificationCodeRepository.findByPurposeAndCode(VerificationCodePurpose.RESET_PASSWORD, "123456"))
                .thenReturn(Optional.of(resetCode));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authenticationService.resetPassword(request)
        );

        assertEquals("Code is invalid or has expired", exception.getMessage());
        verify(userRepository, never()).save(user);
        verify(verificationCodeRepository, never()).delete(resetCode);
        verify(redisTokenService, never()).revokeAllUserTokens(user.getId());
    }

    @Test
    void verifyResetCode_shouldThrowBadRequestExceptionWhenCodeIsExpiredOrInvalid() {
        VerifyCodeRequest request = new VerifyCodeRequest();
        request.setEmail("student@mail.com");
        request.setCode("123456");

        when(verificationCodeRepository.findByPurposeAndCode(VerificationCodePurpose.RESET_PASSWORD, request.getCode()))
                .thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authenticationService.verifyResetCode(request)
        );
        assertEquals("Code is invalid or has expired", exception.getMessage());
    }

    @Test
    void refreshToken_shouldRejectAccessTokenByTokenType() {
        String token = "access-token";
        User user = user(9L, "student@mail.com", true);

        when(redisTokenService.isRefreshTokenRevoked(token)).thenReturn(false);
        when(jwtService.extractUsername(token)).thenReturn(user.getEmail());
        when(userDetailService.loadUserByUsername(user.getEmail())).thenReturn(user);
        when(jwtService.isTokenValid(token, user)).thenReturn(true);
        when(jwtService.isTokenType(token, TokenType.REFRESH)).thenReturn(false);

        UnauthorizedException exception = assertThrows(
                UnauthorizedException.class,
                () -> authenticationService.refreshToken(token)
        );

        assertEquals("Invalid refresh token", exception.getMessage());
        verify(redisTokenService, never()).revokeRefreshTokenIfUnused(eq(token), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refreshToken_shouldReturnUnauthorizedWhenRefreshTokenExpired() {
        String token = "expired-refresh-token";

        when(redisTokenService.isRefreshTokenRevoked(token)).thenReturn(false);
        when(jwtService.extractUsername(token))
                .thenThrow(new ExpiredJwtException(null, null, "expired"));

        UnauthorizedException exception = assertThrows(
                UnauthorizedException.class,
                () -> authenticationService.refreshToken(token)
        );

        assertEquals("Refresh token has expired", exception.getMessage());
        verify(redisTokenService, never()).revokeRefreshTokenIfUnused(eq(token), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refreshToken_shouldUseAtomicRevocationBeforeIssuingNewTokens() {
        String token = "refresh-token";
        User user = user(9L, "student@mail.com", true);
        Date expiration = new Date(System.currentTimeMillis() + 60_000);
        Date issuedAt = new Date(System.currentTimeMillis() - 1_000);

        when(redisTokenService.isRefreshTokenRevoked(token)).thenReturn(false);
        when(jwtService.extractUsername(token)).thenReturn(user.getEmail());
        when(userDetailService.loadUserByUsername(user.getEmail())).thenReturn(user);
        when(jwtService.isTokenValid(token, user)).thenReturn(true);
        when(jwtService.isTokenType(token, TokenType.REFRESH)).thenReturn(true);
        when(jwtService.extractUserId(token)).thenReturn(user.getId());
        when(jwtService.extractIssuedAt(token)).thenReturn(issuedAt);
        when(redisTokenService.isTokenRevokedByUserInvalidation(user.getId(), issuedAt)).thenReturn(false);
        when(jwtService.extractExpiration(token)).thenReturn(expiration);
        when(redisTokenService.revokeRefreshTokenIfUnused(token, expiration)).thenReturn(true);
        when(jwtService.generateAccessToken(user, 600_000L)).thenReturn("new-access-token");
        when(jwtService.generateRefreshToken(user, 360_000_000L)).thenReturn("new-refresh-token");

        AuthenticationResponse response = authenticationService.refreshToken(token);

        assertEquals("new-access-token", response.getAccessToken());
        assertEquals("new-refresh-token", response.getRefreshToken());
        verify(redisTokenService).revokeRefreshTokenIfUnused(token, expiration);
    }

    @Test
    void refreshToken_shouldRejectReusedRefreshTokenAtomically() {
        String token = "refresh-token";
        User user = user(9L, "student@mail.com", true);
        Date expiration = new Date(System.currentTimeMillis() + 60_000);
        Date issuedAt = new Date(System.currentTimeMillis() - 1_000);

        when(redisTokenService.isRefreshTokenRevoked(token)).thenReturn(false);
        when(jwtService.extractUsername(token)).thenReturn(user.getEmail());
        when(userDetailService.loadUserByUsername(user.getEmail())).thenReturn(user);
        when(jwtService.isTokenValid(token, user)).thenReturn(true);
        when(jwtService.isTokenType(token, TokenType.REFRESH)).thenReturn(true);
        when(jwtService.extractUserId(token)).thenReturn(user.getId());
        when(jwtService.extractIssuedAt(token)).thenReturn(issuedAt);
        when(redisTokenService.isTokenRevokedByUserInvalidation(user.getId(), issuedAt)).thenReturn(false);
        when(jwtService.extractExpiration(token)).thenReturn(expiration);
        when(redisTokenService.revokeRefreshTokenIfUnused(token, expiration)).thenReturn(false);

        UnauthorizedException exception = assertThrows(
                UnauthorizedException.class,
                () -> authenticationService.refreshToken(token)
        );

        assertEquals("Refresh token has been revoked", exception.getMessage());
        verify(jwtService, never()).generateAccessToken(user, 600_000L);
        verify(jwtService, never()).generateRefreshToken(user, 360_000_000L);
    }

    @Test
    void logout_shouldRevokeRefreshTokenWhenAccessTokenIsNotUsable() {
        String refreshToken = "refresh-token";
        String accessToken = "expired-access-token";
        User user = user(9L, "student@mail.com", true);
        Date expiration = new Date(System.currentTimeMillis() + 60_000);
        Date issuedAt = new Date(System.currentTimeMillis() - 1_000);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);

        when(redisTokenService.isRefreshTokenRevoked(refreshToken)).thenReturn(false);
        when(jwtService.extractUsername(refreshToken)).thenReturn(user.getEmail());
        when(userDetailService.loadUserByUsername(user.getEmail())).thenReturn(user);
        when(jwtService.isTokenValid(refreshToken, user)).thenReturn(true);
        when(jwtService.isTokenType(refreshToken, TokenType.REFRESH)).thenReturn(true);
        when(jwtService.extractUserId(refreshToken)).thenReturn(user.getId());
        when(jwtService.extractIssuedAt(refreshToken)).thenReturn(issuedAt);
        when(redisTokenService.isTokenRevokedByUserInvalidation(user.getId(), issuedAt)).thenReturn(false);
        when(jwtService.extractExpiration(refreshToken)).thenReturn(expiration);
        when(redisTokenService.revokeRefreshTokenIfUnused(refreshToken, expiration)).thenReturn(true);
        when(jwtService.isTokenType(accessToken, TokenType.ACCESS)).thenThrow(new RuntimeException("expired"));

        authenticationService.logout(request, refreshToken);

        verify(redisTokenService).revokeRefreshTokenIfUnused(refreshToken, expiration);
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
