package com.example.teacherassistantai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateClassroomRequest {

    @NotBlank(message = "Classroom name must not be blank")
    @Size(max = 100, message = "Classroom name must be <= 100 characters")
    private String name;

    @NotBlank(message = "Academic year must not be blank")
    @Size(max = 20, message = "Academic year must be <= 20 characters")
    private String academicYear;

    @Size(max = 20, message = "Semester must be <= 20 characters")
    private String semester;

    @Size(max = 1000, message = "Description must be <= 1000 characters")
    private String description;

    @NotNull(message = "Active flag is required")
    private Boolean active;
}

