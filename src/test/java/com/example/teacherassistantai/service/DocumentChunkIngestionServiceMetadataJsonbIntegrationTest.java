package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import com.example.teacherassistantai.common.enumerate.SubjectType;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.integration.ai.AiEmbeddingGateway;
import com.example.teacherassistantai.repository.DocumentRepository;
import com.example.teacherassistantai.repository.SubjectRepository;
import com.example.teacherassistantai.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.domain.AuditorAware;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@ActiveProfiles("dev")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        DocumentChunkIngestionService.class,
        ChunkMetadataBuilder.class,
        DocumentChunkIngestionServiceMetadataJsonbIntegrationTest.TestConfig.class
})
@Transactional
class DocumentChunkIngestionServiceMetadataJsonbIntegrationTest {

    @Autowired
    private DocumentChunkIngestionService ingestionService;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void ingest_persists_metadata_jsonb_as_json_object() {
        Subject subject = subjectRepository.save(Subject.builder()
                .name("Subject-" + UUID.randomUUID())
                .code("SUB-" + UUID.randomUUID().toString().substring(0, 8))
                .subjectType(SubjectType.TEXT_BASED)
                .build());

        User uploader = userRepository.save(User.builder()
                .fullName("Uploader Test")
                .email("uploader-" + UUID.randomUUID() + "@example.com")
                .password("secret123")
                .enabled(true)
                .roles(new HashSet<>())
                .build());

        Document document = documentRepository.save(Document.builder()
                .title("Intro document")
                .originalObjectKey("uploads/test/" + UUID.randomUUID() + ".pdf")
                .fileType("PDF")
                .fileSizeBytes(1024L)
                .subject(subject)
                .uploadedBy(uploader)
                .status(DocumentStatus.UPLOADED)
                .build());

        List<DocumentChunk> chunks = ingestionService.ingest(document, "# Header\n\nSimple test content");
        assertEquals(1, chunks.size());

        Long chunkId = chunks.getFirst().getId();
        Object jsonType = entityManager.createNativeQuery("""
                SELECT jsonb_typeof(metadata_jsonb)
                FROM document_chunks
                WHERE id = ?
                """)
                .setParameter(1, chunkId)
                .getSingleResult();

        Object chunkType = entityManager.createNativeQuery("""
                SELECT metadata_jsonb ->> 'chunkType'
                FROM document_chunks
                WHERE id = ?
                """)
                .setParameter(1, chunkId)
                .getSingleResult();

        assertEquals("object", jsonType);
        assertEquals("TEXT", chunkType);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        MarkdownChunkingService markdownChunkingService() {
            return new MarkdownChunkingService() {
                @Override
                public List<String> chunk(String markdown) {
                    return List.of("fixed chunk for metadata-jsonb test");
                }
            };
        }

        @Bean
        @Primary
        AiEmbeddingGateway aiEmbeddingGateway() {
            return new AiEmbeddingGateway() {
                @Override
                public List<Double> embed(String input) {
                    return IntStream.range(0, 1024).mapToObj(i -> 0.01d).toList();
                }

                @Override
                public List<List<Double>> embedAll(List<String> inputs) {
                    List<Double> vec = IntStream.range(0, 1024).mapToObj(i -> 0.01d).toList();
                    return inputs.stream().map(t -> vec).toList();
                }

                @Override
                public String embeddingModel() {
                    return "qwen3-embedding-0.6b";
                }
            };
        }

        @Bean
        @Primary
        RagProperties ragProperties() {
            RagProperties properties = new RagProperties();
            properties.setEmbeddingDimensions(1024);
            return properties;
        }

        @Bean(name = "auditorProvider")
        AuditorAware<Long> auditorProvider() {
            return () -> Optional.of(1L);
        }

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager();
        }
    }
}
