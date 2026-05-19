package com.example.teacherassistantai.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateChatSessionRequest {

    @NotNull
    @Min(1)
    private Long subjectId;

    private String title;
}

