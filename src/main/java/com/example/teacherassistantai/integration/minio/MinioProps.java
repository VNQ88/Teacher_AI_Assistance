package com.example.teacherassistantai.integration.minio;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "application.minio")
public class MinioProps {

    @NotBlank
    private String endpoint;

    @NotBlank
    private String serverUrl;

    @NotBlank
    private String accessKey;

    @NotBlank
    private String secretKey;

    /** Bucket dùng mặc định để lưu object. */
    @NotBlank
    private String bucket;

    /** Tiền tố cho object key, ví dụ: "uploads/" (có thể để trống). */
    private String keyPrefix = "";

    /** TTL cho pre-signed URL (giây). */
    private int presignExpirySeconds = 1500;

    /**
     * CHỈ bật nếu bạn chắc chắn muốn public đọc GET cho toàn bucket (không khuyến nghị).
     * Mặc định: private.
     */
    private boolean makeBucketPublic = false;
}

