package com.example.teacherassistantai.dto.request;

import com.example.teacherassistantai.common.enumerate.SubjectType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateSubjectRequest {

    private Boolean active = true;

    private SubjectType subjectType = SubjectType.TEXT_BASED;

    @Size(max =1000, message = "Description must be <=1000 characters")
    private String description;

    @Size(max =100, message = "Subject name must be <=100 characters")
    @NotBlank(message = "Subject name must not be blank")
    private String name;
}
