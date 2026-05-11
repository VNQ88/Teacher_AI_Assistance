package com.example.teacherassistantai.integration.ai;

public interface AiChatGateway {

    String generateAnswer(String prompt, Double temperature, AiWorkload workload);

    default String generateAnswer(String prompt, Double temperature) {
        return generateAnswer(prompt, temperature, AiWorkload.INTERACTIVE);
    }
}
