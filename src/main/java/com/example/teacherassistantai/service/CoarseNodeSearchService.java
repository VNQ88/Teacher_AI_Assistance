package com.example.teacherassistantai.service;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.integration.ai.AiEmbeddingGateway;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CoarseNodeSearchService {

    private final DocumentNodeArtifactRepository artifactRepository;
    private final AiEmbeddingGateway embeddingGateway;
    private final RagProperties ragProperties;

    public List<DocumentNodeArtifactRepository.CoarseNodeHit> search(Long subjectId, String queryEmbedding) {
        if (subjectId == null || !StringUtils.hasText(queryEmbedding)) {
            return List.of();
        }
        RagProperties.Retrieval.CoarseToFine config = ragProperties.getRetrieval().getCoarseToFine();
        return artifactRepository.searchCompletedSummaryArtifactsVector(
                subjectId,
                queryEmbedding,
                embeddingModel(),
                ragProperties.getEmbeddingDimensions(),
                config.isIncludeDocumentRoot(),
                config.getMaxCoarseDistance(),
                Math.max(1, config.getCoarseTopK())
        );
    }

    private String embeddingModel() {
        String model = embeddingGateway.embeddingModel();
        if (StringUtils.hasText(model)) {
            return model;
        }
        return ragProperties.getAi().getEmbeddingModel();
    }
}
