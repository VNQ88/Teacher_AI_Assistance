package com.example.teacherassistantai.integration.ai;

import com.example.teacherassistantai.config.RagProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DigitalOceanEmbeddingGateway implements AiEmbeddingGateway {

    private final EmbeddingModel embeddingModel;
    private final RagProperties ragProperties;

    public DigitalOceanEmbeddingGateway(EmbeddingModel embeddingModel,
                                        RagProperties ragProperties) {
        this.embeddingModel = embeddingModel;
        this.ragProperties = ragProperties;
    }

    @Override
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

    @Override
    public String embeddingModel() {
        return ragProperties.getAi().getEmbeddingModel();
    }
}
