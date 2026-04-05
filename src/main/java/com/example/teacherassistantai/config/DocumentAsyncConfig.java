package com.example.teacherassistantai.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@RequiredArgsConstructor
public class DocumentAsyncConfig {

    private final DocumentIngestionProps ingestionProps;

    @Bean(name = "documentProcessingExecutor")
    public ThreadPoolTaskExecutor documentProcessingExecutor() {
        int maxPool = Math.max(1, Math.min(2, ingestionProps.getParseConcurrency()));
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("doc-process-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(maxPool);
        executor.setQueueCapacity(20);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
