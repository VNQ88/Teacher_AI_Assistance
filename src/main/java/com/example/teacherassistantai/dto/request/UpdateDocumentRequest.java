package com.example.teacherassistantai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateDocumentRequest {

    @NotBlank(message = "Title must not be blank")
    @Size(max = 255, message = "Title must be <= 255 characters")
    private String title;

    @Size(max = 1000, message = "Description must be <= 1000 characters")
    private String description;
}
