package com.example.teacherassistantai.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChangePasswordRequest {
    @NotBlank(message = "Old password must not be blank")
    private String oldPassword;
    @NotBlank(message = "New password must not be blank")
    private String newPassword;
    @NotBlank(message = "Confirm password must not be blank")
    private String confirmPassword;
}
