package com.example.teacherassistantai.integration.ai;

import java.util.List;

public interface AiEmbeddingGateway {

    List<Double> embed(String input);

    String embeddingModel();
}
