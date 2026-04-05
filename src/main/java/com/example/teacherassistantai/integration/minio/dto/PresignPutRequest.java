package com.example.teacherassistantai.integration.minio.dto;

import com.example.teacherassistantai.common.enumerate.ResourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PresignPutRequest {
    @NotBlank
    private String contentType;   // MIME type: image/png, video/mp4...

    private int ttlSeconds = 3600; // default 60 phút

    @NotNull
    private ResourceType resourceType;

    // Nếu resourceType = LESSON thì cần courseId
    private Long courseId;
}
