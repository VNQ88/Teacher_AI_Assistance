package com.example.teacherassistantai.integration.gemini;

import com.example.teacherassistantai.config.RagProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class GeminiEmbeddingGateway {

    private final EmbeddingModel embeddingModel;
    private final RagProperties ragProperties;

    public GeminiEmbeddingGateway(EmbeddingModel embeddingModel,
                                  RagProperties ragProperties) {
        this.embeddingModel = embeddingModel;
        this.ragProperties = ragProperties;
    }

    public List<Double> embed(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }

        float[] output = embeddingModel.embed(input);
        if (output == null || output.length == 0) {
            return List.of();
        }

        List<Double> values = new ArrayList<>(output.length);
        for (float value : output) {
            values.add((double) value);
        }
        return values;
    }

    public String embeddingModel() {
        return ragProperties.getGemini().getEmbeddingModel();
    }
}
