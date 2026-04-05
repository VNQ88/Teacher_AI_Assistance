package com.example.teacherassistantai.dto.response;

import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentResponse {
    private Long id;
    private String title;
    private String description;
    private Long subjectId;
    private String subjectName;
    private Long classroomId;
    private String classroomName;
    private String fileType;
    private Long fileSizeBytes;
    private String originalObjectKey;
    private String markdownObjectKey;
    private DocumentStatus status;
    private String processingError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

