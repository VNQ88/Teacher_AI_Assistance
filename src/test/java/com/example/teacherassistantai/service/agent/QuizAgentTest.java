package com.example.teacherassistantai.service.agent;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.service.DocumentNodeScopeService;
import com.example.teacherassistantai.service.RagArtifactChatHandlerService;
import com.example.teacherassistantai.service.quiz.HierarchicalQuizEnrichmentService;
import com.example.teacherassistantai.service.quiz.HierarchicalQuizEnrichmentService.QuizArtifactOutcome;
import com.example.teacherassistantai.service.quiz.QuizGenerationStrategy;
import com.example.teacherassistantai.service.quiz.ReviewQuestionCompositionService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuizAgentTest {

    @Test
    void execute_completedQuizArtifactReturnsSourcesFromCitations() {
        Fixture fixture = fixture(Map.of("questions", List.of(Map.of("citations", List.of(Map.of("chunkId", 200L))))));
        DocumentNodeArtifactRepository artifactRepository = mock(DocumentNodeArtifactRepository.class);
        RagArtifactChatHandlerService handlerService = mock(RagArtifactChatHandlerService.class);
        DocumentChunk source = chunk();
        QuizAgent quizAgent = new QuizAgent(
                artifactRepository,
                handlerService,
                mock(DocumentNodeScopeService.class),
                mock(QuizGenerationStrategy.class),
                mock(HierarchicalQuizEnrichmentService.class),
                mock(RedisTemplate.class),
                mock(ReviewQuestionCompositionService.class)
        );

        when(artifactRepository.findLatestByNodeTypeAndStatus(
                100L,
                DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                DocumentNodeArtifactStatus.COMPLETED
        )).thenReturn(List.of(fixture.artifact()));
        when(handlerService.renderQuestions(fixture.content(), fixture.node())).thenReturn("Bộ câu hỏi");
        when(handlerService.sourceChunksFromCitations(fixture.content())).thenReturn(List.of(source));

        AgentResult result = quizAgent.execute(state(fixture.node()));

        assertThat(result.answer()).isEqualTo("Bộ câu hỏi");
        assertThat(result.sources()).containsExactly(source);
    }

    @Test
    void execute_completedQuizWithoutCitationsStillReturnsAnswer() {
        Fixture fixture = fixture(Map.of("questions", List.of(Map.of("question", "Câu hỏi?"))));
        DocumentNodeArtifactRepository artifactRepository = mock(DocumentNodeArtifactRepository.class);
        RagArtifactChatHandlerService handlerService = mock(RagArtifactChatHandlerService.class);
        QuizAgent quizAgent = new QuizAgent(
                artifactRepository,
                handlerService,
                mock(DocumentNodeScopeService.class),
                mock(QuizGenerationStrategy.class),
                mock(HierarchicalQuizEnrichmentService.class),
                mock(RedisTemplate.class),
                mock(ReviewQuestionCompositionService.class)
        );

        when(artifactRepository.findLatestByNodeTypeAndStatus(
                100L,
                DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                DocumentNodeArtifactStatus.COMPLETED
        )).thenReturn(List.of(fixture.artifact()));
        when(handlerService.renderQuestions(fixture.content(), fixture.node())).thenReturn("Bộ câu hỏi");
        when(handlerService.sourceChunksFromCitations(fixture.content())).thenReturn(List.of());

        AgentResult result = quizAgent.execute(state(fixture.node()));

        assertThat(result.answer()).isEqualTo("Bộ câu hỏi");
        assertThat(result.sources()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_onDemandGeneratedQuizReturnsSourcesFromCitations() {
        Fixture fixture = fixture(Map.of("questions", List.of(Map.of("citations", List.of(Map.of("chunkId", 200L))))));
        DocumentNodeArtifactRepository artifactRepository = mock(DocumentNodeArtifactRepository.class);
        RagArtifactChatHandlerService handlerService = mock(RagArtifactChatHandlerService.class);
        DocumentNodeScopeService nodeScopeService = mock(DocumentNodeScopeService.class);
        QuizGenerationStrategy strategy = mock(QuizGenerationStrategy.class);
        HierarchicalQuizEnrichmentService enrichmentService = mock(HierarchicalQuizEnrichmentService.class);
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        DocumentChunk source = chunk();
        QuizAgent quizAgent = new QuizAgent(
                artifactRepository,
                handlerService,
                nodeScopeService,
                strategy,
                enrichmentService,
                redisTemplate,
                mock(ReviewQuestionCompositionService.class)
        );

        when(artifactRepository.findLatestByNodeTypeAndStatus(
                100L,
                DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                DocumentNodeArtifactStatus.COMPLETED
        )).thenReturn(List.of(), List.of(fixture.artifact()));
        when(redisTemplate.hasKey("artifact-lock:100:REVIEW_QUESTION_SET")).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("od"), any(Duration.class))).thenReturn(true);
        when(nodeScopeService.getScope(100L))
                .thenReturn(new DocumentNodeScopeService.NodeScope(fixture.node(), List.of(source), "hash"));
        when(strategy.determine(fixture.node(), 1))
                .thenReturn(QuizGenerationStrategy.QuizInputType.RAW_CHUNKS);
        when(enrichmentService.generateAndSaveQuizArtifactOnDemand(
                fixture.node(),
                QuizGenerationStrategy.QuizInputType.RAW_CHUNKS
        )).thenReturn(QuizArtifactOutcome.COMPLETED);
        when(handlerService.renderQuestions(fixture.content(), fixture.node())).thenReturn("Bộ câu hỏi mới");
        when(handlerService.sourceChunksFromCitations(fixture.content())).thenReturn(List.of(source));

        AgentResult result = quizAgent.execute(state(fixture.node()));

        assertThat(result.answer()).isEqualTo("Bộ câu hỏi mới");
        assertThat(result.sources()).containsExactly(source);
        verify(enrichmentService).generateAndSaveQuizArtifactOnDemand(
                fixture.node(),
                QuizGenerationStrategy.QuizInputType.RAW_CHUNKS
        );
    }

    @Test
    void execute_partUsesCompositionFlow() {
        DocumentNodeArtifactRepository artifactRepository = mock(DocumentNodeArtifactRepository.class);
        ReviewQuestionCompositionService compositionService = mock(ReviewQuestionCompositionService.class);
        DocumentChunk source = chunk();
        DocumentNode part = node(300L, "part", "Phần I");
        QuizAgent quizAgent = new QuizAgent(
                artifactRepository,
                mock(RagArtifactChatHandlerService.class),
                mock(DocumentNodeScopeService.class),
                mock(QuizGenerationStrategy.class),
                mock(HierarchicalQuizEnrichmentService.class),
                mock(RedisTemplate.class),
                compositionService
        );

        when(compositionService.composeForPart(part)).thenReturn(
                new ReviewQuestionCompositionService.ReviewQuestionCompositionResult(
                        "Bộ câu hỏi Phần I",
                        List.of(source),
                        List.of(),
                        List.of(),
                        true,
                        true
                )
        );

        AgentResult result = quizAgent.execute(state(part));

        assertThat(result.answer()).isEqualTo("Bộ câu hỏi Phần I");
        assertThat(result.sources()).containsExactly(source);
        verify(artifactRepository, never()).findLatestByNodeTypeAndStatus(any(), any(), any());
    }

    @Test
    void execute_documentUsesCompositionFlow() {
        ReviewQuestionCompositionService compositionService = mock(ReviewQuestionCompositionService.class);
        DocumentNode documentNode = node(400L, "document", "Toàn bộ tài liệu");
        QuizAgent quizAgent = new QuizAgent(
                mock(DocumentNodeArtifactRepository.class),
                mock(RagArtifactChatHandlerService.class),
                mock(DocumentNodeScopeService.class),
                mock(QuizGenerationStrategy.class),
                mock(HierarchicalQuizEnrichmentService.class),
                mock(RedisTemplate.class),
                compositionService
        );

        when(compositionService.composeForDocument(documentNode)).thenReturn(
                new ReviewQuestionCompositionService.ReviewQuestionCompositionResult(
                        "Bộ câu hỏi toàn bộ tài liệu",
                        List.of(),
                        List.of(),
                        List.of(),
                        true,
                        true
                )
        );

        AgentResult result = quizAgent.execute(state(documentNode));

        assertThat(result.answer()).isEqualTo("Bộ câu hỏi toàn bộ tài liệu");
        verify(compositionService).composeForDocument(documentNode);
    }

    @Test
    void execute_chapterUsesNodeLevelArtifactFlow() {
        DocumentNodeArtifactRepository artifactRepository = mock(DocumentNodeArtifactRepository.class);
        RagArtifactChatHandlerService handlerService = mock(RagArtifactChatHandlerService.class);
        ReviewQuestionCompositionService compositionService = mock(ReviewQuestionCompositionService.class);
        DocumentNode chapter = node(600L, "chapter", "Chương 1");
        Map<String, Object> content = Map.of("questions", List.of(Map.of("question", "Câu hỏi?")));
        DocumentNodeArtifact artifact = DocumentNodeArtifact.builder()
                .documentNode(chapter)
                .artifactType(DocumentNodeArtifactType.REVIEW_QUESTION_SET)
                .status(DocumentNodeArtifactStatus.COMPLETED)
                .contentJsonb(content)
                .build();
        QuizAgent quizAgent = new QuizAgent(
                artifactRepository,
                handlerService,
                mock(DocumentNodeScopeService.class),
                mock(QuizGenerationStrategy.class),
                mock(HierarchicalQuizEnrichmentService.class),
                mock(RedisTemplate.class),
                compositionService
        );

        when(artifactRepository.findLatestByNodeTypeAndStatus(
                600L,
                DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                DocumentNodeArtifactStatus.COMPLETED
        )).thenReturn(List.of(artifact));
        when(handlerService.renderQuestions(content, chapter)).thenReturn("Bộ câu hỏi Chương 1");
        when(handlerService.sourceChunksFromCitations(content)).thenReturn(List.of());

        AgentResult result = quizAgent.execute(state(chapter));

        assertThat(result.answer()).isEqualTo("Bộ câu hỏi Chương 1");
        verify(compositionService, never()).composeForChapter(chapter);
    }

    @Test
    void execute_supportNodesAreRejected() {
        QuizAgent quizAgent = new QuizAgent(
                mock(DocumentNodeArtifactRepository.class),
                mock(RagArtifactChatHandlerService.class),
                mock(DocumentNodeScopeService.class),
                mock(QuizGenerationStrategy.class),
                mock(HierarchicalQuizEnrichmentService.class),
                mock(RedisTemplate.class),
                mock(ReviewQuestionCompositionService.class)
        );

        AgentResult result = quizAgent.execute(state(node(500L, "summary", "TÓM TẮT CHƯƠNG 1")));

        assertThat(result.answer()).contains("Không thể tạo bộ câu hỏi trực tiếp");
        assertThat(result.artifactHit()).isFalse();
    }

    private RagChatState state(DocumentNode node) {
        return RagChatState.builder()
                .resolvedNode(node)
                .question("Tạo bộ câu hỏi")
                .build();
    }

    private Fixture fixture(Map<String, Object> content) {
        Document document = Document.builder()
                .title("Giáo trình")
                .build();
        document.setId(10L);
        DocumentNode node = node(100L, "section", "Mục 1");
        node.setDocument(document);
        DocumentNodeArtifact artifact = DocumentNodeArtifact.builder()
                .documentNode(node)
                .artifactType(DocumentNodeArtifactType.REVIEW_QUESTION_SET)
                .status(DocumentNodeArtifactStatus.COMPLETED)
                .contentJsonb(content)
                .build();
        return new Fixture(node, artifact, content);
    }

    private DocumentNode node(Long id, String nodeType, String title) {
        Document document = Document.builder()
                .title("Giáo trình")
                .build();
        document.setId(10L);
        DocumentNode node = DocumentNode.builder()
                .document(document)
                .nodeType(nodeType)
                .title(title)
                .sectionPath(title)
                .build();
        node.setId(id);
        return node;
    }

    private DocumentChunk chunk() {
        DocumentChunk chunk = DocumentChunk.builder()
                .content("Nguồn")
                .chunkIndex(1)
                .build();
        chunk.setId(200L);
        return chunk;
    }

    private record Fixture(DocumentNode node, DocumentNodeArtifact artifact, Map<String, Object> content) {
    }
}
