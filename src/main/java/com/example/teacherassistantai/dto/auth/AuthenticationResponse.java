package com.example.teacherassistantai.dto.auth;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class AuthenticationResponse {
    private String accessToken;
    private String refreshToken;
}
