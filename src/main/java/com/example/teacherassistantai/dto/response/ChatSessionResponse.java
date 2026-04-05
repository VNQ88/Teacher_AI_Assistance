package com.example.teacherassistantai.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ChatSessionResponse {
    private Long id;
    private Long subjectId;
    private String subjectName;
    private Long classroomId;
    private String classroomName;
    private String title;
    private Boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

