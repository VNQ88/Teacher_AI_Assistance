package com.example.teacherassistantai.integration.gemini;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class GeminiChatGateway {

    private final ChatClient chatClient;

    public GeminiChatGateway(@Qualifier("geminiChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

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
