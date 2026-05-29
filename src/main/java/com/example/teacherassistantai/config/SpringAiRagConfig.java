package com.example.teacherassistantai.config;

import com.example.teacherassistantai.integration.ai.RateLimitTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;

@Slf4j
@Configuration
public class SpringAiRagConfig {

    @Bean(name = "ragChatClient")
    public ChatClient ragChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.build();
    }

    @Bean
    public RestClientCustomizer openAiTimeoutCustomizer(RagProperties ragProperties,
                                                        RateLimitTracker rateLimitTracker) {
        return builder -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(10));
            factory.setReadTimeout(Duration.ofSeconds(ragProperties.getAi().getTimeoutSeconds()));
            builder.requestFactory(factory);

            builder.requestInterceptor((request, body, execution) -> {
                var response = execution.execute(request, body);
                String rem = response.getHeaders().getFirst("ratelimit-remaining");
                String reset = response.getHeaders().getFirst("ratelimit-reset");
                if (rem != null && reset != null) {
                    try {
                        rateLimitTracker.update(Integer.parseInt(rem), Long.parseLong(reset));
                    } catch (NumberFormatException e) {
                        log.debug("Could not parse rate limit headers: remaining={} reset={}", rem, reset);
                    }
                }
                return response;
            });
        };
    }
}
