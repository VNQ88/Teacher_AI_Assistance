package com.example.teacherassistantai.service;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.integration.gemini.GeminiEmbeddingGateway;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DocumentChunkIngestionService {

    private final MarkdownChunkingService markdownChunkingService;
    private final ChunkMetadataBuilder chunkMetadataBuilder;
    private final GeminiEmbeddingGateway embeddingGateway;
    private final DocumentChunkRepository documentChunkRepository;
    private final RagProperties ragProperties;

    @Transactional
    public List<DocumentChunk> ingest(Document document, String markdown) {
        List<String> chunks = markdownChunkingService.chunk(markdown);
        List<DocumentChunk> persisted = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            String content = chunks.get(i);
            DocumentChunk chunk = DocumentChunk.builder()
                    .document(document)
                    .subjectId(document.getSubject().getId())
                    .classroomId(document.getClassroom() != null ? document.getClassroom().getId() : null)
                    .chunkIndex(i + 1)
                    .chunkType("TEXT")
                    .content(content)
                    .tokenCount(Math.max(1, content.length() / 4))
                    .metadataJsonb(chunkMetadataBuilder.buildJsonb(null, null, null, "TEXT", content.length()))
                    .build();

            DocumentChunk saved = documentChunkRepository.save(chunk);
            List<Double> embedding = embeddingGateway.embed(content);
            validateEmbeddingDimensions(embedding, saved.getId());
            String embeddingLiteral = toVectorLiteral(embedding);
            documentChunkRepository.updateEmbeddingLiteral(saved.getId(), embeddingLiteral);
            persisted.add(saved);
        }

        return persisted;
    }

    private void validateEmbeddingDimensions(List<Double> embedding, Long chunkId) {
        int actual = embedding == null ? 0 : embedding.size();
        int expected = ragProperties.getEmbeddingDimensions();
        if (actual != expected) {
            throw new InvalidDataException("Embedding dimension mismatch for chunk %d: expected %d, got %d"
                    .formatted(chunkId, expected, actual));
        }
    }

    public String toVectorLiteral(List<Double> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) builder.append(',');
            builder.append(values.get(i));
        }
        builder.append(']');
        return builder.toString();
    }
}
