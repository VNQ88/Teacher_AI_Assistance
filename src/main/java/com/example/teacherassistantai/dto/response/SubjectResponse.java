package com.example.teacherassistantai.dto.response;

import com.example.teacherassistantai.common.enumerate.SubjectType;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SubjectResponse {
    private Long id;
    private String name;
    private String code;
    private String description;
    private SubjectType subjectType;
    private Boolean active;
}

