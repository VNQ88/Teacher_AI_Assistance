package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.DocumentChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagConfidenceServiceTest {

    private final RagConfidenceService service = new RagConfidenceService();

    @Test
    void level_shouldUseUpdatedThresholds() {
        assertEquals("LOW", service.level(0.54));
        assertEquals("MEDIUM", service.level(0.55));
        assertEquals("HIGH", service.level(0.80));
    }

    @Test
    void score_shouldIncreaseWhenAnswerContainsEvidenceCitations() {
        DocumentChunk chunk = DocumentChunk.builder()
                .content("Phan 2 noi ve dong tu bat quy tac")
                .build();
        chunk.setId(88L);

        double withoutCitation = service.score(
                "Phan 2 noi gi",
                List.of(chunk),
                "Phan 2 noi ve dong tu bat quy tac"
        );

        double withCitation = service.score(
                "Phan 2 noi gi",
                List.of(chunk),
                "Phan 2 noi ve dong tu bat quy tac [Chunk 88]"
        );

        assertTrue(withCitation > withoutCitation);
    }
}

