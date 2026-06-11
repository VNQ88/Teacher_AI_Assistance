package com.example.teacherassistantai.integration.ai;

import com.example.teacherassistantai.config.RagProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Profile("!security-test")
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
    public List<List<Double>> embedAll(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) return List.of();
        EmbeddingResponse response = embeddingModel.call(new EmbeddingRequest(inputs, null));
        List<List<Double>> result = new ArrayList<>(response.getResults().size());
        for (var embedding : response.getResults()) {
            result.add(floatToDoubleList(embedding.getOutput()));
        }
        return result;
    }

    private List<Double> floatToDoubleList(float[] floats) {
        List<Double> values = new ArrayList<>(floats.length);
        for (float v : floats) values.add((double) v);
        return values;
    }

    @Override
    public String embeddingModel() {
        return ragProperties.getAi().getEmbeddingModel();
    }
}
