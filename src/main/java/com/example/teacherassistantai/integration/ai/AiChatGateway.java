package com.example.teacherassistantai.integration.ai;

public interface AiChatGateway {

    String generateAnswer(String prompt, Double temperature, AiWorkload workload);

    default AiChatCompletion generate(String prompt, Double temperature, AiWorkload workload) {
        return new AiChatCompletion(
                generateAnswer(prompt, temperature, workload),
                null,
                null,
                null,
                workload == null ? AiWorkload.RAG_CHAT : workload.normalized()
        );
    }

    default String generateAnswer(String prompt, Double temperature) {
        return generateAnswer(prompt, temperature, AiWorkload.RAG_CHAT);
    }
}
