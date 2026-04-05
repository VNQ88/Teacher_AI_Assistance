package com.example.teacherassistantai.integration.minio;


import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.net.URI;
import java.time.Duration;

@EnableConfigurationProperties(MinioProps.class)
@Configuration
@RequiredArgsConstructor
public class MinioConfig {

    private final MinioProps props;

    private OkHttpClient httpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofMinutes(5))
                .writeTimeout(Duration.ofMinutes(5))
                .callTimeout(Duration.ofMinutes(5))
                .build();
    }

    private static String sanitizeEndpoint(String raw) {
        URI u = URI.create(raw);
        return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
    }

    private MinioClient buildClient(String endpoint) {
        return MinioClient.builder()
                .endpoint(sanitizeEndpoint(endpoint))
                .credentials(props.getAccessKey(), props.getSecretKey())
                .httpClient(httpClient())
                .build();
    }

    /** Client nội bộ: http://minio:9000 */
    @Bean("minioInternalClient")
    @Primary
    public MinioClient minioInternalClient() {
        return buildClient(props.getEndpoint());
    }

    /** Client public để presign: https://minio.social.io.vn */
    @Bean("minioPresignClient")
    public MinioClient minioPresignClient() {
        return buildClient(props.getServerUrl());
    }
}

