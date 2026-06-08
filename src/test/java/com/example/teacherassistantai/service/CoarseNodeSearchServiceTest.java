package com.example.teacherassistantai.service;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.integration.ai.AiEmbeddingGateway;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CoarseNodeSearchServiceTest {

    private DocumentNodeArtifactRepository artifactRepository;
    private AiEmbeddingGateway embeddingGateway;
    private RagProperties ragProperties;
    private CoarseNodeSearchService service;

    @BeforeEach
    void setUp() {
        artifactRepository = mock(DocumentNodeArtifactRepository.class);
        embeddingGateway = mock(AiEmbeddingGateway.class);
        ragProperties = new RagProperties();
        ragProperties.setEmbeddingDimensions(1024);
        ragProperties.getRetrieval().getCoarseToFine().setCoarseTopK(5);
        ragProperties.getRetrieval().getCoarseToFine().setMaxCoarseDistance(0.42);
        service = new CoarseNodeSearchService(artifactRepository, embeddingGateway, ragProperties);
    }

    @Test
    void search_shouldUseSubjectModelDimensionsAndCoarseConfig() {
        when(embeddingGateway.embeddingModel()).thenReturn("embed-v1");
        List<DocumentNodeArtifactRepository.CoarseNodeHit> hits = List.of(hit(11L, 21L, 0.12));
        when(artifactRepository.searchCompletedSummaryArtifactsVector(
                7L,
                "[0.1,0.2]",
                "embed-v1",
                1024,
                false,
                0.42,
                5
        )).thenReturn(hits);

        List<DocumentNodeArtifactRepository.CoarseNodeHit> result = service.search(7L, "[0.1,0.2]");

        assertThat(result).isSameAs(hits);
        verify(artifactRepository).searchCompletedSummaryArtifactsVector(
                7L,
                "[0.1,0.2]",
                "embed-v1",
                1024,
                false,
                0.42,
                5
        );
    }

    @Test
    void search_shouldReturnEmptyWhenSubjectOrEmbeddingIsMissing() {
        assertThat(service.search(null, "[0.1,0.2]")).isEmpty();
        assertThat(service.search(7L, " ")).isEmpty();

        verifyNoInteractions(artifactRepository);
    }

    @Test
    void search_shouldUseCurrentConfiguredModelAndDimensionsForStaleEmbeddingFilter() {
        when(embeddingGateway.embeddingModel()).thenReturn("");
        when(artifactRepository.searchCompletedSummaryArtifactsVector(
                7L,
                "[0.1,0.2]",
                "qwen3-embedding-0.6b",
                1024,
                false,
                0.42,
                5
        )).thenReturn(List.of());

        service.search(7L, "[0.1,0.2]");

        verify(artifactRepository).searchCompletedSummaryArtifactsVector(
                7L,
                "[0.1,0.2]",
                "qwen3-embedding-0.6b",
                1024,
                false,
                0.42,
                5
        );
    }

    @Test
    void search_shouldIncludeDocumentRootWhenTuningFlagEnabled() {
        ragProperties.getRetrieval().getCoarseToFine().setIncludeDocumentRoot(true);
        when(embeddingGateway.embeddingModel()).thenReturn("embed-v1");

        service.search(7L, "[0.1,0.2]");

        verify(artifactRepository).searchCompletedSummaryArtifactsVector(
                7L,
                "[0.1,0.2]",
                "embed-v1",
                1024,
                true,
                0.42,
                5
        );
    }

    private DocumentNodeArtifactRepository.CoarseNodeHit hit(Long artifactId, Long nodeId, Double distance) {
        return new DocumentNodeArtifactRepository.CoarseNodeHit() {
            @Override
            public Long getArtifactId() {
                return artifactId;
            }

            @Override
            public Long getNodeId() {
                return nodeId;
            }

            @Override
            public Long getDocumentId() {
                return 31L;
            }

            @Override
            public String getDocumentTitle() {
                return "Document";
            }

            @Override
            public String getNodeType() {
                return "section";
            }

            @Override
            public String getSectionPath() {
                return "Chương 1 > Mục 1";
            }

            @Override
            public Double getDistance() {
                return distance;
            }
        };
    }
}
