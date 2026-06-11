package com.example.teacherassistantai.config;

import com.example.teacherassistantai.integration.ai.AiChatCompletion;
import com.example.teacherassistantai.integration.ai.AiChatGateway;
import com.example.teacherassistantai.integration.ai.AiEmbeddingGateway;
import com.example.teacherassistantai.integration.ai.AiWorkload;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.List;

@Configuration
@Profile("security-test")
public class SecurityTestAiConfig {

    @Bean
    public AiChatGateway securityTestAiChatGateway() {
        return new AiChatGateway() {
            @Override
            public String generateAnswer(String prompt, Double temperature, AiWorkload workload) {
                return "Security test AI sandbox response.";
            }

            @Override
            public AiChatCompletion generate(String prompt, Double temperature, AiWorkload workload) {
                AiWorkload normalized = workload == null ? AiWorkload.RAG_CHAT : workload.normalized();
                return new AiChatCompletion(
                        "Security test AI sandbox response.",
                        null,
                        null,
                        "security-test-sandbox",
                        normalized
                );
            }
        };
    }

    @Bean
    public AiEmbeddingGateway securityTestAiEmbeddingGateway(RagProperties ragProperties) {
        return new AiEmbeddingGateway() {
            @Override
            public List<Double> embed(String input) {
                return sandboxVector(ragProperties.getEmbeddingDimensions());
            }

            @Override
            public List<List<Double>> embedAll(List<String> inputs) {
                if (inputs == null || inputs.isEmpty()) {
                    return List.of();
                }
                List<List<Double>> result = new ArrayList<>(inputs.size());
                for (int i = 0; i < inputs.size(); i++) {
                    result.add(sandboxVector(ragProperties.getEmbeddingDimensions()));
                }
                return result;
            }

            @Override
            public String embeddingModel() {
                return "security-test-sandbox";
            }
        };
    }

    private static List<Double> sandboxVector(int dimensions) {
        int safeDimensions = Math.max(1, dimensions);
        List<Double> vector = new ArrayList<>(safeDimensions);
        vector.add(1.0d);
        for (int i = 1; i < safeDimensions; i++) {
            vector.add(0.0d);
        }
        return vector;
    }
}
