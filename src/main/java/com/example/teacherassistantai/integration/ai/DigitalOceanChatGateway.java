package com.example.teacherassistantai.integration.ai;

import com.example.teacherassistantai.exception.AiRateLimitedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DigitalOceanChatGateway implements AiChatGateway {

    private final ChatClient chatClient;
    private final DigitalOceanAiRateLimiter rateLimiter;

    public DigitalOceanChatGateway(@Qualifier("ragChatClient") ChatClient chatClient,
                                   DigitalOceanAiRateLimiter rateLimiter) {
        this.chatClient = chatClient;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public String generateAnswer(String prompt, Double temperature, AiWorkload workload) {
        if (prompt == null || prompt.isBlank()) {
            return "Khong co cau hoi hop le.";
        }

        rateLimiter.acquire(workload);

        try {
            String content = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            return content != null ? content : "Xin loi, he thong tam thoi chua tao duoc cau tra loi.";
        } catch (Exception ex) {
            if (is429(ex)) {
                log.warn("429 received workload={}", workload);
                if (workload == AiWorkload.BACKGROUND) {
                    rateLimiter.handleBackground429(); // sets pause and throws BackgroundRateLimitedException
                }
                throw new AiRateLimitedException("AI provider rate limit exceeded. Please retry later.");
            }
            throw ex;
        }
    }

    private boolean is429(Exception ex) {
        String msg = ex.getMessage();
        return msg != null && msg.contains("429");
    }
}
