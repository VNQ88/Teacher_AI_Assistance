package com.example.teacherassistantai.integration.email;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum EmailTemplateName {
    ACTIVATE_ACCOUNT("activate-account"),
    RESET_PASSWORD("reset-password");

    private final String templateName;
}