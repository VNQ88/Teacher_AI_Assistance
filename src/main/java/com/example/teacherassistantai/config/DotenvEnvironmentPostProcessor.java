package com.example.teacherassistantai.config;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvBuilder;
import io.github.cdimascio.dotenv.DotenvEntry;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "dotenv";
    private static final String DOTENV_DIRECTORY = "DOTENV_DIRECTORY";
    private static final String DOTENV_FILENAME = "DOTENV_FILENAME";
    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        DotenvBuilder builder = Dotenv.configure()
                .ignoreIfMissing();

        String directory = environment.getProperty(DOTENV_DIRECTORY);
        if (hasText(directory)) {
            builder.directory(directory);
        }

        String filename = environment.getProperty(DOTENV_FILENAME);
        if (hasText(filename)) {
            builder.filename(filename);
        }

        Dotenv dotenv = builder.load();
        Map<String, Object> properties = new LinkedHashMap<>();
        for (DotenvEntry entry : dotenv.entries()) {
            properties.put(entry.getKey(), entry.getValue());
        }

        if (properties.isEmpty()) {
            return;
        }

        MutablePropertySources propertySources = environment.getPropertySources();
        if (propertySources.contains(PROPERTY_SOURCE_NAME)) {
            propertySources.replace(PROPERTY_SOURCE_NAME, new MapPropertySource(PROPERTY_SOURCE_NAME, properties));
            return;
        }
        if (propertySources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            propertySources.addAfter(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new MapPropertySource(PROPERTY_SOURCE_NAME, properties)
            );
            return;
        }
        propertySources.addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, properties));
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
