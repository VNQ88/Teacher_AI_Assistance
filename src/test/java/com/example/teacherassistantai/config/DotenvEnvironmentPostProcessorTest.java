package com.example.teacherassistantai.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DotenvEnvironmentPostProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void postProcessEnvironment_shouldLoadDotenvIntoEnvironment() throws IOException {
        Files.writeString(tempDir.resolve(".env"), "DOTENV_TEST_VALUE=dotenv-value\n");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getSystemProperties().put("DOTENV_DIRECTORY", tempDir.toString());

        try {
            new DotenvEnvironmentPostProcessor()
                    .postProcessEnvironment(environment, new SpringApplication());

            assertThat(environment.getProperty("DOTENV_TEST_VALUE")).isEqualTo("dotenv-value");
            assertThat(environment.getPropertySources().contains(DotenvEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                    .isTrue();
        } finally {
            environment.getSystemProperties().remove("DOTENV_DIRECTORY");
        }
    }

    @Test
    void postProcessEnvironment_shouldKeepSystemPropertiesBeforeDotenv() throws IOException {
        Files.writeString(tempDir.resolve(".env"), "DOTENV_TEST_VALUE=dotenv-value\n");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getSystemProperties().put("DOTENV_DIRECTORY", tempDir.toString());
        environment.getSystemProperties().put("DOTENV_TEST_VALUE", "system-value");

        try {
            new DotenvEnvironmentPostProcessor()
                    .postProcessEnvironment(environment, new SpringApplication());

            assertThat(environment.getProperty("DOTENV_TEST_VALUE")).isEqualTo("system-value");
        } finally {
            environment.getSystemProperties().remove("DOTENV_DIRECTORY");
            environment.getSystemProperties().remove("DOTENV_TEST_VALUE");
        }
    }
}
