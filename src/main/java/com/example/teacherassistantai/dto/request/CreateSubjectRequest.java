package com.example.teacherassistantai.dto.request;

import com.example.teacherassistantai.common.enumerate.SubjectType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateSubjectRequest {

    @NotBlank(message = "Subject name must not be blank")
    @Size(max = 100, message = "Subject name must be <= 100 characters")
    private String name;

    @NotBlank(message = "Subject code must not be blank")
    @Size(max = 20, message = "Subject code must be <= 20 characters")
    private String code;

    @Size(max = 1000, message = "Description must be <= 1000 characters")
    private String description;

    @NotNull(message = "Subject type is required")
    private SubjectType subjectType;
}

