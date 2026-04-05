package com.example.teacherassistantai.controller;

import com.example.teacherassistantai.common.response.ResponseData;
import com.example.teacherassistantai.common.response.ResponseError;
import com.example.teacherassistantai.dto.auth.AuthenticationRequest;
import com.example.teacherassistantai.dto.auth.RegistrationRequest;
import com.example.teacherassistantai.dto.auth.SetNewPasswordRequest;
import com.example.teacherassistantai.dto.auth.VerifyCodeRequest;
import com.example.teacherassistantai.repository.UserRepository;
import com.example.teacherassistantai.security.JwtService;
import com.example.teacherassistantai.security.UserDetailServiceImpl;
import com.example.teacherassistantai.service.AuthenticationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.mail.MessagingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("auth")
@RequiredArgsConstructor
@SecurityRequirements
@Tag(name = "Authentication Controller")
public class AuthenticationController {
    private final AuthenticationService authenticationService;
    private final UserRepository userRepository;
    private final UserDetailServiceImpl userDetailServiceImpl;
    private final JwtService jwtService;

    @Value("${application.jwt.valid-duration}")
    private long validDuration;
    @Value("${application.jwt.refreshable-duration}")
    private long refreshDuration;

    @Operation(
            summary = "User login",
            description = "Xác thực người dùng bằng email/password và trả về access token + refresh token."
    )
    @PostMapping("/authenticate")
    public ResponseData<?> authenticate(@RequestBody @Valid AuthenticationRequest authenticationRequest) {
        return new ResponseData<>(HttpStatus.OK.value(), "Authentication successful",
                authenticationService.authenticate(authenticationRequest));
    }

    @Operation(
            summary = "User registration",
            description = "Đăng ký tài khoản mới. Sau khi đăng ký thành công, hệ thống sẽ gửi email kích hoạt."
    )
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResponseData<?> register(@RequestBody @Valid RegistrationRequest registrationRequest) throws MessagingException {
        authenticationService.register(registrationRequest);
        return new ResponseData<>(HttpStatus.ACCEPTED.value(), "Registration successful. Please check your email to activate your account.");
    }

    @Operation(
            summary = "Refresh token",
            description = "Dùng refresh token để lấy access token mới."
    )
    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResponseData<?> refreshToken(HttpServletRequest request) {
        return new ResponseData<>(HttpStatus.ACCEPTED.value(), "Token refreshed successfully",
                authenticationService.refreshToken(request));
    }

    @Operation(
            summary = "Logout",
            description = "Đăng xuất người dùng hiện tại, vô hiệu hóa refresh token."
    )
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.OK)
    public ResponseData<?> logout(HttpServletRequest request) {
        authenticationService.logout(request);
        return new ResponseData<>(HttpStatus.OK.value(), "Logout successful");
    }

    @Operation(
            summary = "Activate account",
            description = "Kích hoạt tài khoản bằng mã code đã được gửi qua email."
    )
    @PostMapping("/activate-account")
    public ResponseData<?> confirmAccount(@RequestParam String code) throws MessagingException {
        authenticationService.activateAccount(code);
        return new ResponseData<>(HttpStatus.ACCEPTED.value(), "Account activated.");
    }

    @Operation(
            summary = "Forgot password",
            description = "Gửi email đặt lại mật khẩu cho người dùng."
    )
    @PostMapping("/forgot-password")
    public ResponseData<?> forgotPassword(@RequestParam @NotBlank String email) throws MessagingException {
        return new ResponseData<>(HttpStatus.OK.value(),
                authenticationService.forgotPassword(email));
    }


    @Operation(
            summary = "Verify reset code",
            description = "Xác minh mã code đặt lại mật khẩu đã được gửi qua email."
    )
    @PostMapping("/verify-reset-code")
    public ResponseData<?> verifyResetCode(@RequestBody @Valid VerifyCodeRequest request) throws MessagingException {
        authenticationService.verifyResetCode(request);
        return new ResponseData<>(HttpStatus.OK.value(), "Code verified successfully");
    }

    @Operation(
            summary = "Resend activation code",
            description = "Gửi lại mã kích hoạt tài khoản bằng email (Dùng khi mã cũ bị hết hạn)."
    )
    @PostMapping("/resend-activation-code")
    public ResponseData<?> resendActivationCode(@RequestParam @NotBlank String email) throws MessagingException {
        authenticationService.resendActivationCode(email);
        return new ResponseData<>(HttpStatus.OK.value(), "A new activation code has been sent to your email.");
    }

    @Operation(
            summary = "Reset password",
            description = "Đặt lại mật khẩu bằng mã code đã được gửi qua email."
    )
    @PostMapping("/reset-password")
    public ResponseData<?> resetPassword(@RequestBody @Valid SetNewPasswordRequest request){
        authenticationService.resetPassword(request);
        return new ResponseData<>(HttpStatus.OK.value(), "Password has been reset successfully");
    }

}
