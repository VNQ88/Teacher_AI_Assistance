package com.example.teacherassistantai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAiRagConfig {

    @Bean(name = "geminiChatClient")
    public ChatClient geminiChatClient(ChatClient.Builder chatClientBuilder) {
        // Model and default temperature come from spring.ai.openai.* properties.
        return chatClientBuilder.build();
    }
}
