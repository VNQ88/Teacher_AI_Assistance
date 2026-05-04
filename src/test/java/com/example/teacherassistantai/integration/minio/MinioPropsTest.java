package com.example.teacherassistantai.integration.minio;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class MinioPropsTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void binding_shouldAcceptCompleteMinioConfiguration() {
        contextRunner
                .withPropertyValues(
                        "application.minio.endpoint=http://localhost:9100",
                        "application.minio.server-url=http://localhost:9100",
                        "application.minio.access-key=minio",
                        "application.minio.secret-key=minio123",
                        "application.minio.bucket=resources"
                )
                .run(context -> {
                    MinioProps properties = context.getBean(MinioProps.class);

                    assertThat(properties.getEndpoint()).isEqualTo("http://localhost:9100");
                    assertThat(properties.getServerUrl()).isEqualTo("http://localhost:9100");
                    assertThat(properties.getAccessKey()).isEqualTo("minio");
                    assertThat(properties.getSecretKey()).isEqualTo("minio123");
                    assertThat(properties.getBucket()).isEqualTo("resources");
                });
    }

    @Test
    void binding_shouldFailWhenCredentialsAreBlank() {
        contextRunner
                .withPropertyValues(
                        "application.minio.endpoint=http://localhost:9100",
                        "application.minio.server-url=http://localhost:9100",
                        "application.minio.access-key=",
                        "application.minio.secret-key=",
                        "application.minio.bucket=resources"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(MinioProps.class)
    static class TestConfig {
    }
}
