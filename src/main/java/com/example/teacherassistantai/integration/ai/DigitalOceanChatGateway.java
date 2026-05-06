package com.example.teacherassistantai.integration.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DigitalOceanChatGateway implements AiChatGateway {

    private final ChatClient chatClient;

    public DigitalOceanChatGateway(@Qualifier("ragChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String generateAnswer(String prompt, Double temperature) {
        if (prompt == null || prompt.isBlank()) {
            return "Khong co cau hoi hop le.";
        }

        // Keep method signature for service compatibility; per-request temperature override is optional.
        String content = chatClient.prompt()
                .user(prompt)
                .call()
                .content();
        return content != null ? content : "Xin loi, he thong tam thoi chua tao duoc cau tra loi.";
    }
}
