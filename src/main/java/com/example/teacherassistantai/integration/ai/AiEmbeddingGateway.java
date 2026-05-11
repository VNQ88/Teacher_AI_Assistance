package com.example.teacherassistantai.integration.ai;

import java.util.List;

public interface AiEmbeddingGateway {

    List<Double> embed(String input);

    List<List<Double>> embedAll(List<String> inputs);

    String embeddingModel();
}
