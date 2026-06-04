package com.example.teacherassistantai.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SetNewPasswordRequest {
    @Email
    @NotBlank(message = "Email is required")
    private String email;
    @NotBlank
    private String code;
    @NotBlank
    private String newPassword;
    @NotBlank
    private String confirmPassword;
}
