package com.example.teacherassistantai.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VerifyCodeRequest {
    @Email
    private String email;
    @NotBlank
    private String code;
}
