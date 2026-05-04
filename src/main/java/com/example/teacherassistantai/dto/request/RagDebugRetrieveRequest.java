package com.example.teacherassistantai.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RagDebugRetrieveRequest {

    @NotNull
    @Min(1)
    private Long sessionId;

    @NotBlank
    private String question;

    @Min(1)
    @Max(20)
    private Integer topK = 6;
}
