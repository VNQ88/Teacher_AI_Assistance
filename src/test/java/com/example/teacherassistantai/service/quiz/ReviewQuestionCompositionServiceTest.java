package com.example.teacherassistantai.service.quiz;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.integration.ai.DigitalOceanAiRateLimiter;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import com.example.teacherassistantai.service.DocumentEnrichmentBacklogService;
import com.example.teacherassistantai.service.InternalCitationSanitizer;
import com.example.teacherassistantai.service.RagArtifactChatHandlerService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewQuestionCompositionServiceTest {

    @Test
    void composeForPart_allChaptersCompletedReturnsGroupedAnswerAndSources() {
        Fixture fixture = fixture();
        DocumentNode ch1 = chapter(101L, "Chương 1");
        DocumentNode ch2 = chapter(102L, "Chương 2");
        DocumentChunk source = sourceChunk();
        ReviewQuestionCompositionService service = service(fixture);

        when(fixture.nodeRepository.findByParentIdAndNodeTypeOrderByOrderIndexAsc(10L, "chapter"))
                .thenReturn(List.of(ch1, ch2));
        whenCompletedArtifacts(fixture, ch1, ch2);
        when(fixture.backlogService.hasSummaryBacklog(1L)).thenReturn(false);
        when(fixture.rateLimiter.isBackgroundPaused()).thenReturn(false);
        when(fixture.handlerService.sourceChunksFromCitations(any())).thenReturn(List.of(source));

        ReviewQuestionCompositionService.ReviewQuestionCompositionResult result =
                service.composeForPart(fixture.part);

        assertThat(result.hasAnyCompletedChapter()).isTrue();
        assertThat(result.fullyCovered()).isTrue();
        assertThat(result.answer()).contains("Bộ câu hỏi ôn tập Phần I");
        assertThat(result.answer()).contains("### Chương 1");
        assertThat(result.answer()).contains("### Chương 2");
        assertThat(result.sources()).containsExactly(source);
    }

    @Test
    void composeForPart_someMissingQueuesMissingAndReturnsAvailableQuestions() {
        Fixture fixture = fixture();
        DocumentNode ch1 = chapter(101L, "Chương 1");
        DocumentNode ch2 = chapter(102L, "Chương 2");
        ReviewQuestionCompositionService service = service(fixture);

        when(fixture.nodeRepository.findByParentIdAndNodeTypeOrderByOrderIndexAsc(10L, "chapter"))
                .thenReturn(List.of(ch1, ch2));
        when(fixture.artifactRepository.findByNodeIdsAndArtifactTypeAndStatusOrderByNodeOrderAndUpdatedAt(
                any(), any(), any()
        )).thenReturn(List.of(artifact(ch1, 2)));
        when(fixture.backlogService.hasSummaryBacklog(1L)).thenReturn(false);
        when(fixture.rateLimiter.isBackgroundPaused()).thenReturn(false);
        when(fixture.handlerService.sourceChunksFromCitations(any())).thenReturn(List.of());

        ReviewQuestionCompositionService.ReviewQuestionCompositionResult result =
                service.composeForPart(fixture.part);

        assertThat(result.hasAnyCompletedChapter()).isTrue();
        assertThat(result.fullyCovered()).isFalse();
        assertThat(result.missingChapters()).containsExactly(ch2);
        assertThat(result.queuedChapters()).containsExactly(ch2);
        assertThat(result.answer()).contains("Các chương sau đang được tạo thêm câu hỏi");
        verify(fixture.enrichmentService).enqueueChapterQuizGeneration(102L);
    }

    @Test
    void composeForPart_noneCompletedReturnsProgressMessageAndQueuesMissing() {
        Fixture fixture = fixture();
        DocumentNode ch1 = chapter(101L, "Chương 1");
        DocumentNode ch2 = chapter(102L, "Chương 2");
        ReviewQuestionCompositionService service = service(fixture);

        when(fixture.nodeRepository.findByParentIdAndNodeTypeOrderByOrderIndexAsc(10L, "chapter"))
                .thenReturn(List.of(ch1, ch2));
        when(fixture.artifactRepository.findByNodeIdsAndArtifactTypeAndStatusOrderByNodeOrderAndUpdatedAt(
                any(), any(), any()
        )).thenReturn(List.of());
        when(fixture.backlogService.hasSummaryBacklog(1L)).thenReturn(false);
        when(fixture.rateLimiter.isBackgroundPaused()).thenReturn(false);

        ReviewQuestionCompositionService.ReviewQuestionCompositionResult result =
                service.composeForPart(fixture.part);

        assertThat(result.hasAnyCompletedChapter()).isFalse();
        assertThat(result.answer()).contains("đang được tạo theo từng chương");
        verify(fixture.enrichmentService).enqueueChapterQuizGeneration(101L);
        verify(fixture.enrichmentService).enqueueChapterQuizGeneration(102L);
    }

    @Test
    void composeForDocument_groupsQuestionsByPartAndChapter() {
        Fixture fixture = fixture();
        DocumentNode part2 = part(20L, "Phần II");
        DocumentNode ch1 = chapter(101L, "Chương 1");
        DocumentNode ch2 = chapter(201L, "Chương 2");
        ReviewQuestionCompositionService service = service(fixture);

        when(fixture.nodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(1L, "part"))
                .thenReturn(List.of(fixture.part, part2));
        when(fixture.nodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(1L, "chapter"))
                .thenReturn(List.of(ch1, ch2));
        when(fixture.nodeRepository.findByParentIdAndNodeTypeOrderByOrderIndexAsc(10L, "chapter"))
                .thenReturn(List.of(ch1));
        when(fixture.nodeRepository.findByParentIdAndNodeTypeOrderByOrderIndexAsc(20L, "chapter"))
                .thenReturn(List.of(ch2));
        whenCompletedArtifacts(fixture, ch1, ch2);
        when(fixture.backlogService.hasSummaryBacklog(1L)).thenReturn(false);
        when(fixture.rateLimiter.isBackgroundPaused()).thenReturn(false);
        when(fixture.handlerService.sourceChunksFromCitations(any())).thenReturn(List.of());

        ReviewQuestionCompositionService.ReviewQuestionCompositionResult result =
                service.composeForDocument(fixture.documentNode);

        assertThat(result.answer()).contains("Bộ câu hỏi ôn tập toàn bộ tài liệu Giáo trình");
        assertThat(result.answer()).contains("## Phần I");
        assertThat(result.answer()).contains("## Phần II");
        assertThat(result.answer()).contains("### Chương 1");
        assertThat(result.answer()).contains("### Chương 2");
    }

    @Test
    void composeForPart_respectsPerChapterLimit() {
        Fixture fixture = fixture();
        DocumentNode ch1 = chapter(101L, "Chương 1");
        ReviewQuestionCompositionService service = service(fixture);

        fixture.ragProperties.getEnrichment().getReviewQuestionComposition()
                .setMaxQuestionsPerChapterInPart(5);
        when(fixture.nodeRepository.findByParentIdAndNodeTypeOrderByOrderIndexAsc(10L, "chapter"))
                .thenReturn(List.of(ch1));
        when(fixture.artifactRepository.findByNodeIdsAndArtifactTypeAndStatusOrderByNodeOrderAndUpdatedAt(
                any(), any(), any()
        )).thenReturn(List.of(artifact(ch1, 25)));
        when(fixture.backlogService.hasSummaryBacklog(1L)).thenReturn(false);
        when(fixture.rateLimiter.isBackgroundPaused()).thenReturn(false);
        when(fixture.handlerService.sourceChunksFromCitations(any())).thenReturn(List.of());

        ReviewQuestionCompositionService.ReviewQuestionCompositionResult result =
                service.composeForPart(fixture.part);

        assertThat(result.answer()).contains("5. [TRUE_FALSE");
        assertThat(result.answer()).doesNotContain("6. [TRUE_FALSE");
    }

    @Test
    void composeForPart_summaryBacklogBlocksMissingQueue() {
        Fixture fixture = fixture();
        DocumentNode ch1 = chapter(101L, "Chương 1");
        ReviewQuestionCompositionService service = service(fixture);

        when(fixture.nodeRepository.findByParentIdAndNodeTypeOrderByOrderIndexAsc(10L, "chapter"))
                .thenReturn(List.of(ch1));
        when(fixture.artifactRepository.findByNodeIdsAndArtifactTypeAndStatusOrderByNodeOrderAndUpdatedAt(
                any(), any(), any()
        )).thenReturn(List.of());
        when(fixture.backlogService.hasSummaryBacklog(1L)).thenReturn(true);
        when(fixture.rateLimiter.isBackgroundPaused()).thenReturn(false);

        ReviewQuestionCompositionService.ReviewQuestionCompositionResult result =
                service.composeForPart(fixture.part);

        assertThat(result.answer()).contains("summary sẵn sàng");
        verify(fixture.enrichmentService, never()).enqueueChapterQuizGeneration(any());
    }

    @Test
    void composeForPart_ratePauseBlocksMissingQueue() {
        Fixture fixture = fixture();
        DocumentNode ch1 = chapter(101L, "Chương 1");
        ReviewQuestionCompositionService service = service(fixture);

        when(fixture.nodeRepository.findByParentIdAndNodeTypeOrderByOrderIndexAsc(10L, "chapter"))
                .thenReturn(List.of(ch1));
        when(fixture.artifactRepository.findByNodeIdsAndArtifactTypeAndStatusOrderByNodeOrderAndUpdatedAt(
                any(), any(), any()
        )).thenReturn(List.of());
        when(fixture.backlogService.hasSummaryBacklog(1L)).thenReturn(false);
        when(fixture.rateLimiter.isBackgroundPaused()).thenReturn(true);

        ReviewQuestionCompositionService.ReviewQuestionCompositionResult result =
                service.composeForPart(fixture.part);

        assertThat(result.answer()).contains("tạm dừng do giới hạn AI");
        verify(fixture.enrichmentService, never()).enqueueChapterQuizGeneration(any());
    }

    private ReviewQuestionCompositionService service(Fixture fixture) {
        return new ReviewQuestionCompositionService(
                fixture.nodeRepository,
                fixture.artifactRepository,
                fixture.enrichmentService,
                fixture.backlogService,
                fixture.rateLimiter,
                fixture.ragProperties,
                fixture.handlerService,
                new InternalCitationSanitizer()
        );
    }

    private void whenCompletedArtifacts(Fixture fixture, DocumentNode... chapters) {
        when(fixture.artifactRepository.findByNodeIdsAndArtifactTypeAndStatusOrderByNodeOrderAndUpdatedAt(
                any(), any(), any()
        )).thenReturn(java.util.Arrays.stream(chapters)
                .map(chapter -> artifact(chapter, 2))
                .toList());
    }

    private DocumentNodeArtifact artifact(DocumentNode chapter, int questionCount) {
        return DocumentNodeArtifact.builder()
                .document(chapter.getDocument())
                .documentNode(chapter)
                .artifactType(DocumentNodeArtifactType.REVIEW_QUESTION_SET)
                .status(DocumentNodeArtifactStatus.COMPLETED)
                .promptVersion("test")
                .model("test")
                .sourceHash("hash-" + chapter.getId())
                .contentJsonb(Map.of("questions", questions(questionCount)))
                .build();
    }

    private List<Map<String, Object>> questions(int count) {
        List<Map<String, Object>> questions = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            questions.add(Map.of(
                    "type", "TRUE_FALSE",
                    "difficulty", "EASY",
                    "question", "Câu hỏi " + i + "?",
                    "correctAnswer", true,
                    "answerExplanation", "Giải thích " + i,
                    "citations", List.of(Map.of("chunkId", 1000L + i))
            ));
        }
        return questions;
    }

    private Fixture fixture() {
        Document document = Document.builder()
                .title("Giáo trình")
                .build();
        document.setId(1L);
        DocumentNode documentNode = DocumentNode.builder()
                .document(document)
                .nodeType("document")
                .title("Tài liệu")
                .sectionPath("Tài liệu")
                .orderIndex(0)
                .build();
        documentNode.setId(2L);
        DocumentNode part = part(10L, "Phần I");
        return new Fixture(
                document,
                documentNode,
                part,
                mock(DocumentNodeRepository.class),
                mock(DocumentNodeArtifactRepository.class),
                mock(HierarchicalQuizEnrichmentService.class),
                mock(DocumentEnrichmentBacklogService.class),
                mock(DigitalOceanAiRateLimiter.class),
                new RagProperties(),
                mock(RagArtifactChatHandlerService.class)
        );
    }

    private DocumentNode part(Long id, String title) {
        Document document = Document.builder()
                .title("Giáo trình")
                .build();
        document.setId(1L);
        DocumentNode node = DocumentNode.builder()
                .document(document)
                .nodeType("part")
                .title(title)
                .sectionPath(title)
                .orderIndex(id.intValue())
                .build();
        node.setId(id);
        return node;
    }

    private DocumentNode chapter(Long id, String title) {
        Document document = Document.builder()
                .title("Giáo trình")
                .build();
        document.setId(1L);
        DocumentNode node = DocumentNode.builder()
                .document(document)
                .nodeType("chapter")
                .title(title)
                .sectionPath(title)
                .orderIndex(id.intValue())
                .build();
        node.setId(id);
        return node;
    }

    private DocumentChunk sourceChunk() {
        DocumentChunk chunk = DocumentChunk.builder()
                .content("source")
                .chunkIndex(1)
                .build();
        chunk.setId(1001L);
        return chunk;
    }

    private record Fixture(
            Document document,
            DocumentNode documentNode,
            DocumentNode part,
            DocumentNodeRepository nodeRepository,
            DocumentNodeArtifactRepository artifactRepository,
            HierarchicalQuizEnrichmentService enrichmentService,
            DocumentEnrichmentBacklogService backlogService,
            DigitalOceanAiRateLimiter rateLimiter,
            RagProperties ragProperties,
            RagArtifactChatHandlerService handlerService
    ) {
    }
}
