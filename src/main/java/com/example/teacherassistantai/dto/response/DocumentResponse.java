package com.example.teacherassistantai.dto.response;

import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import com.example.teacherassistantai.common.enumerate.DocumentEnrichmentStatus;
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
    private String hierarchyObjectKey;
    private String chunksObjectKey;
    private DocumentStatus status;
    private String statusLabel;
    private DocumentEnrichmentStatus enrichmentStatus;
    private String enrichmentStatusLabel;
    private Boolean ragReady;
    private Boolean learningMaterialsReady;
    private String processingError;
    private String enrichmentError;
    private LocalDateTime enrichmentStartedAt;
    private LocalDateTime enrichmentCompletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
