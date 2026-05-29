package com.example.teacherassistantai.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "application.document")
public class DocumentIngestionProps {

    @Min(1)
    private long maxDocxBytes = 102_400;

    @Min(1)
    private long maxTxtBytes = 102_400;

    @Min(1)
    private int parseConcurrency = 2;

    @Min(1)
    private int tikaMaxChars = 5_000_000;
}
