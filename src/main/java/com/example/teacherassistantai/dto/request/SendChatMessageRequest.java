package com.example.teacherassistantai.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SendChatMessageRequest {

    @NotBlank
    private String question;

    @Min(1)
    @Max(20)
    private Integer topK = 6;

    @Min(0)
    @Max(1)
    private Double temperature = 0.2;

    private Boolean includeSources = Boolean.TRUE;
}

