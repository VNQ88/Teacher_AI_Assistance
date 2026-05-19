package com.example.teacherassistantai.service.quiz;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.integration.ai.AiWorkload;
import com.example.teacherassistantai.integration.ai.DigitalOceanAiRateLimiter;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import com.example.teacherassistantai.service.DocumentEnrichmentBacklogService;
import com.example.teacherassistantai.service.DocumentNodeScopeService;
import com.example.teacherassistantai.service.DocumentReadinessService;
import com.example.teacherassistantai.service.LlmDocumentNodeArtifactGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HierarchicalQuizEnrichmentServiceTest {

    @Test
    void enrichQuizPhase1_continuesThroughChapterPhaseWhenNotRateLimited() {
        Document document = Document.builder().title("Giao trinh").build();
        document.setId(1L);
        DocumentNode smallNode = node(11L, "section", document);
        DocumentNode chapterNode = node(21L, "chapter", document);
        DocumentChunk chunk = DocumentChunk.builder().content("Noi dung").build();
        chunk.setId(101L);

        DocumentNodeRepository nodeRepository = mock(DocumentNodeRepository.class);
        DocumentNodeArtifactRepository artifactRepository = mock(DocumentNodeArtifactRepository.class);
        DocumentNodeScopeService nodeScopeService = mock(DocumentNodeScopeService.class);
        QuizGenerationStrategy strategy = mock(QuizGenerationStrategy.class);
        DigitalOceanAiRateLimiter rateLimiter = mock(DigitalOceanAiRateLimiter.class);
        RedisTemplate<String, String> redisTemplate = redisTemplate();

        when(nodeRepository.findByDocumentIdOrderByOrderIndexAsc(1L)).thenReturn(List.of(smallNode));
        when(nodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(1L, "chapter"))
                .thenReturn(List.of(chapterNode));
        when(nodeScopeService.getScope(11L))
                .thenReturn(new DocumentNodeScopeService.NodeScope(smallNode, List.of(chunk), "small-hash"));
        when(strategy.determine(smallNode, 1)).thenReturn(QuizGenerationStrategy.QuizInputType.RAW_CHUNKS);
        when(rateLimiter.isPaused(AiWorkload.ENRICH_REVIEW_QUESTION)).thenReturn(false);
        when(artifactRepository.findLatestByNodeTypeAndStatus(
                eq(11L),
                eq(DocumentNodeArtifactType.REVIEW_QUESTION_SET),
                eq(DocumentNodeArtifactStatus.COMPLETED)
        )).thenReturn(List.of(completedArtifact(smallNode)));
        when(artifactRepository.findLatestByNodeTypeAndStatus(
                eq(21L),
                eq(DocumentNodeArtifactType.REVIEW_QUESTION_SET),
                eq(DocumentNodeArtifactStatus.COMPLETED)
        )).thenReturn(List.of(completedArtifact(chapterNode)));

        HierarchicalQuizEnrichmentService service = new HierarchicalQuizEnrichmentService(
                nodeRepository,
                artifactRepository,
                mock(DocumentRepository.class),
                nodeScopeService,
                mock(LlmDocumentNodeArtifactGenerator.class),
                strategy,
                redisTemplate,
                new RagProperties(),
                mock(TransactionTemplate.class),
                rateLimiter,
                mock(DocumentEnrichmentBacklogService.class),
                mock(com.example.teacherassistantai.integration.ai.AiModelRoutingService.class),
                mock(ReviewQuestionInputResolver.class),
                mock(DocumentReadinessService.class)
        );

        service.enrichQuizPhase1(1L);

        verify(nodeRepository).findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(1L, "chapter");
        verify(artifactRepository).findLatestByNodeTypeAndStatus(
                21L,
                DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                DocumentNodeArtifactStatus.COMPLETED
        );
    }

    @SuppressWarnings("unchecked")
    private RedisTemplate<String, String> redisTemplate() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        return redisTemplate;
    }

    private DocumentNode node(Long id, String nodeType, Document document) {
        DocumentNode node = DocumentNode.builder()
                .document(document)
                .nodeType(nodeType)
                .title(nodeType + " " + id)
                .sectionPath(nodeType + " " + id)
                .orderIndex(id.intValue())
                .build();
        node.setId(id);
        return node;
    }

    private DocumentNodeArtifact completedArtifact(DocumentNode node) {
        return DocumentNodeArtifact.builder()
                .document(node.getDocument())
                .documentNode(node)
                .artifactType(DocumentNodeArtifactType.REVIEW_QUESTION_SET)
                .status(DocumentNodeArtifactStatus.COMPLETED)
                .build();
    }
}
