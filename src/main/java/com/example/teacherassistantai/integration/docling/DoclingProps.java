package com.example.teacherassistantai.integration.docling;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "application.docling")
public class DoclingProps {

    @NotBlank
    private String baseUrl = "http://localhost:5001";

    private int timeoutSeconds = 120;

    @Min(1)
    private int retryAttempts = 3;

    @Min(50)
    private long retryBackoffMillis = 300;

    @Min(1)
    private int maxInflightRequests = 1;

    private Parse parse = new Parse();

    @Data
    public static class Parse {
        private boolean doOcr = false;
        private boolean includeImages = false;
    }
}
