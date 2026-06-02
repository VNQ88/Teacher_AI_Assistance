package com.example.teacherassistantai.service;

import com.example.teacherassistantai.dto.auth.*;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.integration.email.EmailService;
import com.example.teacherassistantai.integration.email.EmailTemplateName;
import com.example.teacherassistantai.integration.redis.RedisToken;
import com.example.teacherassistantai.integration.redis.RedisTokenService;
import com.example.teacherassistantai.integration.redis.verification_code.VerificationCode;
import com.example.teacherassistantai.integration.redis.verification_code.VerificationCodePurpose;
import com.example.teacherassistantai.integration.redis.verification_code.VerificationCodeRepository;
import com.example.teacherassistantai.repository.RoleRepository;
import com.example.teacherassistantai.repository.UserRepository;
import com.example.teacherassistantai.security.JwtService;
import com.example.teacherassistantai.security.UserDetailServiceImpl;
import io.micrometer.common.util.StringUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
@Service
public class AuthenticationService {
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final UserDetailServiceImpl userDetailService;
    private final VerificationCodeRepository verificationCodeRepository;
    private final EmailService emailService;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RedisTokenService redisTokenService;
    private final PasswordEncoder passwordEncoder;

    @Value("${application.jwt.valid-duration}")
    private int validDuration;
    @Value("${application.jwt.refreshable-duration}")
    private int refreshDuration;
    @Value("${application.mailing.front-end.activation-url}")
    private String activationUrl;
    @Value("${application.mailing.front-end.verification-code-length}")
    private int verificationCodeLength;
    @Value("${application.mailing.front-end.verification-code-expiration-minutes}")
    private int verificationCodeExpirationMinutes;

    public AuthenticationResponse authenticate(AuthenticationRequest authenticationRequest) {
        var auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        authenticationRequest.getEmail(),
                        authenticationRequest.getPassword()
                )
        );

        var user = (User) auth.getPrincipal();
        var accessToken = jwtService.generateToken(user, (long) validDuration * 1000);
        var refreshToken = jwtService.generateToken(user, (long) refreshDuration * 1000);

        return AuthenticationResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    @Transactional
    public void register(RegistrationRequest registrationRequest) {
        if (userRepository.existsByEmail(registrationRequest.getEmail())) {
            var existingUser = userDetailService.loadUserByUsername(registrationRequest.getEmail());
            if (existingUser.isEnabled()) {
                throw new RuntimeException("User with this email already exists and is activated");
            } else {
                sendValidationEmail((User) existingUser, EmailTemplateName.ACTIVATE_ACCOUNT, VerificationCodePurpose.ACTIVATE_ACCOUNT);
            }
            return;
        }

        var userRole = roleRepository.findByName("STUDENT")
                .orElseThrow(() -> new RuntimeException("Role STUDENT was not initialized"));
        var user = User.builder()
                .email(registrationRequest.getEmail())
                .password(registrationRequest.getPassword())
                .fullName(registrationRequest.getFullName())
                .enabled(false)
                .roles(Set.of(userRole))
                .build();
        userRepository.save(user);
        sendValidationEmail(user, EmailTemplateName.ACTIVATE_ACCOUNT, VerificationCodePurpose.ACTIVATE_ACCOUNT);
    }

    private void sendValidationEmail(User user, EmailTemplateName templateName, VerificationCodePurpose purpose) {
        var newCode = generateAndSaveVerificationCode(user, purpose);

        emailService.sendEmail(user.getEmail(),
                user.getFullName(),
                templateName,
                activationUrl,
                newCode,
                emailSubject(templateName));
    }

    private String emailSubject(EmailTemplateName templateName) {
        return switch (templateName) {
            case ACTIVATE_ACCOUNT -> "Activate your account";
            case RESET_PASSWORD -> "Reset your password";
        };
    }

    private String generateAndSaveVerificationCode(User user, VerificationCodePurpose purpose) {
        String generatedCode = generateActivationCode(verificationCodeLength);

        // Tính toán TTL ra giây cho Redis
        long ttlInSeconds = verificationCodeExpirationMinutes * 60L;

        verificationCodeRepository.deleteAll(
                verificationCodeRepository.findAllByUserIdAndPurpose(user.getId(), purpose)
        );

        var token = VerificationCode.builder()
                .code(generatedCode)
                .userId(user.getId()) // Lưu ID thay vì object User
                .purpose(purpose)
                .timeToLive(ttlInSeconds) // Giao cho Redis tự xóa khi hết hạn
                .build();
        verificationCodeRepository.save(token);
        return generatedCode;
    }

    private String generateActivationCode(int length) {
        String characters = "0123456789";
        StringBuilder stringBuilder = new StringBuilder();
        SecureRandom secureRandom = new SecureRandom();

        for (int i = 0; i < length; i++) {
            int randomIndex = secureRandom.nextInt(characters.length());
            stringBuilder.append(characters.charAt(randomIndex));
        }

        return stringBuilder.toString();
    }

    @Transactional
    public void activateAccount(String code) {
        VerificationCode savedToken = verifyCode(code, VerificationCodePurpose.ACTIVATE_ACCOUNT);

        // Lấy lại User từ DB bằng userId lưu trong Redis
        var user = userRepository.findById(savedToken.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.setEnabled(true);
        userRepository.save(user);

        // Xóa mã OTP khỏi Redis ngay sau khi xác thực thành công để không bị dùng lại
        verificationCodeRepository.delete(savedToken);
    }

    public void resendActivationCode(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.isEnabled()) {
            throw new RuntimeException("Account is already activated.");
        }

        sendValidationEmail(user, EmailTemplateName.ACTIVATE_ACCOUNT, VerificationCodePurpose.ACTIVATE_ACCOUNT);
    }

    public AuthenticationResponse refreshToken(HttpServletRequest request) {
        log.info("---------- refreshToken ----------");

        final String refreshToken = request.getHeader(HttpHeaders.REFERER);
        if (StringUtils.isBlank(refreshToken)) {
            throw new InvalidDataException("Token must be not blank");
        }
        if (redisTokenService.isRefreshTokenRevoked(refreshToken)) {
            throw new InvalidDataException("Refresh token has been revoked");
        }
        final String userName = jwtService.extractUsername(refreshToken);
        var user = userDetailService.loadUserByUsername(userName);
        if (!jwtService.isTokenValid(refreshToken, user)) {
            throw new InvalidDataException("Not allow access with this token");
        }

        String newAccessToken = jwtService.generateToken(user, (long) validDuration * 1000);
        String newRefreshToken = jwtService.generateToken(user, (long) refreshDuration * 1000);

        redisTokenService.save(RedisToken.builder()
                .id(refreshToken)
                .accessToken(null)
                .refreshToken(refreshToken)
                .expireTime(jwtService.extractExpiration(refreshToken))
                .build());

        return AuthenticationResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .build();
    }

    public void logout(HttpServletRequest request) {
        log.info("---------- logout ----------");
        final String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        final String refreshToken = request.getHeader(HttpHeaders.REFERER);
        if (StringUtils.isBlank(authHeader) || !authHeader.startsWith("Bearer ")) {
            throw new InvalidDataException("Access token must not be blank");
        }
        final String accessToken = authHeader.substring(7);
        if (StringUtils.isBlank(refreshToken)) {
            throw new InvalidDataException("Refresh token must not be blank");
        }

        final String userName = jwtService.extractUsername(accessToken);
        var user = userDetailService.loadUserByUsername(userName);
        if (!jwtService.isTokenValid(accessToken, user)) {
            throw new InvalidDataException("Invalid access token");
        }
        if (!jwtService.isTokenValid(refreshToken, user)) {
            throw new InvalidDataException("Invalid refresh token");
        }

        redisTokenService.save(RedisToken.builder()
                .id(accessToken)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expireTime(jwtService.extractExpiration(refreshToken))
                .build());
    }

    public String forgotPassword(String email) {
        log.info("---------- forgotPassword ----------");
        if (!userRepository.existsByEmail(email))
            throw new RuntimeException("User with this email does not exist");
        User existingUser = userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (!existingUser.isEnabled()) {
            sendValidationEmail(existingUser, EmailTemplateName.ACTIVATE_ACCOUNT, VerificationCodePurpose.ACTIVATE_ACCOUNT);
            return "Your account is not activated. An activation email has been sent to your email address.";
        }
        else {
            sendValidationEmail(existingUser, EmailTemplateName.RESET_PASSWORD, VerificationCodePurpose.RESET_PASSWORD);
            return "A password reset email has been sent to your email address.";
        }
    }

    public void verifyResetCode(VerifyCodeRequest request)  {
        verifyCode(request.getCode(), VerificationCodePurpose.RESET_PASSWORD);
        // Với Redis, ta không cần đánh dấu 'validatedAt'. Cứ để nguyên đó để hàm resetPassword sử dụng,
        // nếu quá thời gian nó sẽ tự mất.
    }

    @Transactional
    public void resetPassword(SetNewPasswordRequest request) {
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("Password and confirm password do not match");
        }

        VerificationCode resetCode = verifyCode(request.getCode(), VerificationCodePurpose.RESET_PASSWORD);
        User user = userRepository.findById(resetCode.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setEnabled(true);
        userRepository.save(user);

        // Hủy mã OTP khỏi Redis sau khi đổi pass thành công
        verificationCodeRepository.delete(resetCode);
    }


    //    ----------------- Private methods -----------------
    private VerificationCode verifyCode(String code, VerificationCodePurpose purpose){
        // Do Redis có cơ chế tự hủy (TTL), nếu record vẫn còn tồn tại thì tức là còn hạn.
        // Nếu không tìm thấy, nghĩa là mã sai hoặc mã đã hết thời gian lưu trữ.
        if (StringUtils.isBlank(code)) {
            throw new IllegalArgumentException("Code is invalid or has expired");
        }

        return verificationCodeRepository.findByPurposeAndCode(purpose, code.strip())
                .orElseThrow(() -> new IllegalArgumentException("Code is invalid or has expired"));
    }
}
