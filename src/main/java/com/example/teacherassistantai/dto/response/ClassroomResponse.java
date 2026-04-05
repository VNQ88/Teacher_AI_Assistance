package com.example.teacherassistantai.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ClassroomResponse {
    private Long id;
    private String name;
    private String code;
    private String academicYear;
    private String semester;
    private String description;
    private Boolean active;
    private Long subjectId;
    private String subjectName;
    private Long teacherId;
    private String teacherName;
    private int studentCount;
}

