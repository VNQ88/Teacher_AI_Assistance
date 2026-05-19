package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentEnrichmentStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.integration.ai.AiModelRoutingService;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentEnrichmentServiceTest {

    private DocumentRepository documentRepository;
    private DocumentNodeRepository documentNodeRepository;
    private DocumentChunkRepository documentChunkRepository;
    private DocumentNodeArtifactRepository artifactRepository;
    private DocumentNodeScopeService nodeScopeService;
    private ReviewQuestionCountResolver reviewQuestionCountResolver;
    private AiModelRoutingService aiModelRoutingService;
    private OriginalSummaryNodeService originalSummaryNodeService;
    private com.example.teacherassistantai.service.quiz.HierarchicalQuizEnrichmentService quizEnrichmentService;
    private RagProperties ragProperties;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        documentNodeRepository = mock(DocumentNodeRepository.class);
        documentChunkRepository = mock(DocumentChunkRepository.class);
        artifactRepository = mock(DocumentNodeArtifactRepository.class);
        nodeScopeService = mock(DocumentNodeScopeService.class);
        reviewQuestionCountResolver = mock(ReviewQuestionCountResolver.class);
        when(reviewQuestionCountResolver.resolve(any())).thenReturn(new ReviewQuestionCountResolver.CountRange(15, 20));
        aiModelRoutingService = mock(AiModelRoutingService.class);
        originalSummaryNodeService = mock(OriginalSummaryNodeService.class);
        quizEnrichmentService = mock(com.example.teacherassistantai.service.quiz.HierarchicalQuizEnrichmentService.class);
        ragProperties = new RagProperties();
        when(aiModelRoutingService.enrichmentModelFor(any())).thenReturn(ragProperties.getAi().getChatModel());
    }

    @Test
    void enrichDocument_withoutGenerator_marksArtifactsSkippedAndKeepsDocumentReady() {
        Fixture fixture = fixture();
        DocumentEnrichmentService service = service(List.of());
        mockCommonScope(fixture);
        when(quizEnrichmentService.generateAndSaveQuizArtifact(
                eq(fixture.node()),
                any(),
                eq(false)
        )).thenReturn(com.example.teacherassistantai.service.quiz.HierarchicalQuizEnrichmentService.QuizArtifactOutcome.SKIPPED);

        service.enrichDocument(fixture.document().getId(), false);

        ArgumentCaptor<DocumentNodeArtifact> artifactCaptor = ArgumentCaptor.forClass(DocumentNodeArtifact.class);
        verify(artifactRepository, times(1)).save(artifactCaptor.capture());
        assertThat(artifactCaptor.getAllValues())
                .extracting(DocumentNodeArtifact::getStatus)
                .containsOnly(DocumentNodeArtifactStatus.SKIPPED);

        assertThat(fixture.document().getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(fixture.document().getEnrichmentStatus()).isEqualTo(DocumentEnrichmentStatus.SKIPPED);
        assertThat(fixture.document().getEnrichmentError()).contains("Phase 4");
    }

    @Test
    void enrichDocument_withGenerator_completesArtifactsAndMarksDocumentFullUse() {
        Fixture fixture = fixture();
        DocumentNodeArtifactGenerator generator = new DocumentNodeArtifactGenerator() {
            @Override
            public boolean supports(DocumentNodeArtifactType artifactType) {
                return true;
            }

            @Override
            public boolean supportsSummaryMode(SummaryMode summaryMode) {
                return true;
            }

            @Override
            public DocumentNodeArtifactGenerationResult generate(DocumentNodeArtifactGenerationContext context) {
                return new DocumentNodeArtifactGenerationResult(
                        Map.of("artifactType", context.artifactType().name(), "generated", true),
                        42
                );
            }

            @Override
            public DocumentNodeArtifactGenerationResult generateSummary(SummaryGenerationContext context) {
                return new DocumentNodeArtifactGenerationResult(
                        Map.of(
                                "artifactType", DocumentNodeArtifactType.SUMMARY.name(),
                                "summaryMode", context.summaryMode().name(),
                                "summary", "Tóm tắt.",
                                "generated", true
                        ),
                        42
                );
            }
        };
        DocumentEnrichmentService service = service(List.of(generator));
        mockCommonScope(fixture);
        when(quizEnrichmentService.generateAndSaveQuizArtifact(
                eq(fixture.node()),
                any(),
                eq(false)
        )).thenReturn(com.example.teacherassistantai.service.quiz.HierarchicalQuizEnrichmentService.QuizArtifactOutcome.COMPLETED);
        List<DocumentNodeArtifactStatus> savedStatuses = new ArrayList<>();
        when(artifactRepository.save(any(DocumentNodeArtifact.class)))
                .thenAnswer(invocation -> {
                    DocumentNodeArtifact artifact = invocation.getArgument(0);
                    savedStatuses.add(artifact.getStatus());
                    return artifact;
                });
        when(artifactRepository.findByNodeIdsAndArtifactTypeAndStatusOrderByNodeOrderAndUpdatedAt(
                any(),
                eq(DocumentNodeArtifactType.SUMMARY),
                eq(DocumentNodeArtifactStatus.COMPLETED)
        )).thenReturn(List.of(completedSummaryArtifact(fixture.document(), fixture.node(), "Tóm tắt chương")));

        service.enrichDocument(fixture.document().getId(), false);

        verify(artifactRepository, times(2)).save(any(DocumentNodeArtifact.class));
        assertThat(savedStatuses).containsExactly(
                DocumentNodeArtifactStatus.RUNNING,
                DocumentNodeArtifactStatus.COMPLETED
        );

        assertThat(fixture.document().getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(fixture.document().getEnrichmentStatus()).isEqualTo(DocumentEnrichmentStatus.ENRICHED);
        assertThat(fixture.document().getEnrichmentError()).isNull();
    }

    @Test
    void enrichDocument_whenChapterSummaryCompletionAtLeast75Percent_marksDocumentReady() {
        Fixture fixture = fixture();
        List<DocumentNode> chapters = List.of(
                node(fixture.document(), 301L, "chapter", "Chương 1", null),
                node(fixture.document(), 302L, "chapter", "Chương 2", null),
                node(fixture.document(), 303L, "chapter", "Chương 3", null),
                node(fixture.document(), 304L, "chapter", "Chương 4", null)
        );
        DocumentEnrichmentService service = service(List.of(failingSummaryGenerator(List.of(304L))));
        mockDocument(fixture.document());
        mockChapterSummaryBatch(fixture.document(), chapters);
        List<DocumentNodeArtifact> savedArtifacts = captureSavedArtifacts();
        mockCompletedChapterSummaryLookup(savedArtifacts);

        service.enrichDocument(fixture.document().getId(), false, List.of(DocumentNodeArtifactType.SUMMARY));

        assertThat(fixture.document().getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(fixture.document().getEnrichmentStatus()).isEqualTo(DocumentEnrichmentStatus.PARTIAL_FAILED);
        assertThat(fixture.document().getEnrichmentError()).contains("3/4");
    }

    @Test
    void enrichDocument_whenChapterSummaryCompletionBelow75Percent_keepsDocumentSummarising() {
        Fixture fixture = fixture();
        fixture.document().setStatus(DocumentStatus.SUMMARISING);
        List<DocumentNode> chapters = List.of(
                node(fixture.document(), 401L, "chapter", "Chương 1", null),
                node(fixture.document(), 402L, "chapter", "Chương 2", null),
                node(fixture.document(), 403L, "chapter", "Chương 3", null),
                node(fixture.document(), 404L, "chapter", "Chương 4", null)
        );
        DocumentEnrichmentService service = service(List.of(failingSummaryGenerator(List.of(403L, 404L))));
        mockDocument(fixture.document());
        mockChapterSummaryBatch(fixture.document(), chapters);
        List<DocumentNodeArtifact> savedArtifacts = captureSavedArtifacts();
        mockCompletedChapterSummaryLookup(savedArtifacts);

        service.enrichDocument(fixture.document().getId(), false, List.of(DocumentNodeArtifactType.SUMMARY));

        assertThat(fixture.document().getStatus()).isEqualTo(DocumentStatus.SUMMARISING);
        assertThat(fixture.document().getEnrichmentStatus()).isEqualTo(DocumentEnrichmentStatus.PARTIAL_FAILED);
        assertThat(fixture.document().getEnrichmentError()).contains("2/4");
    }

    @Test
    void enrichDocument_summaryOnly_generatesSubsectionBeforeSectionFromChildSummary() {
        Fixture fixture = fixture();
        DocumentNode section = DocumentNode.builder()
                .document(fixture.document())
                .subjectId(7L)
                .nodeKey("s1")
                .nodeType("section")
                .title("Mục 1")
                .sectionPath("Chương 1 > Mục 1")
                .orderIndex(1)
                .build();
        section.setId(101L);
        DocumentNode subsection = DocumentNode.builder()
                .document(fixture.document())
                .parent(section)
                .subjectId(7L)
                .nodeKey("ss1")
                .nodeType("subsection")
                .title("Tiểu mục 1")
                .sectionPath("Chương 1 > Mục 1 > Tiểu mục 1")
                .orderIndex(2)
                .build();
        subsection.setId(102L);
        DocumentChunk subsectionChunk = DocumentChunk.builder()
                .document(fixture.document())
                .subjectId(7L)
                .node(subsection)
                .chunkIndex(1)
                .sourceOrder(1)
                .content("Nội dung tiểu mục")
                .build();
        subsectionChunk.setId(202L);
        DocumentChunk sectionChunk = DocumentChunk.builder()
                .document(fixture.document())
                .subjectId(7L)
                .node(section)
                .chunkIndex(2)
                .sourceOrder(2)
                .content("Nội dung trực tiếp của mục")
                .build();
        sectionChunk.setId(203L);

        List<SummaryMode> modes = new ArrayList<>();
        DocumentNodeArtifactGenerator generator = summaryGenerator(modes);
        DocumentEnrichmentService service = service(List.of(generator));
        mockDocument(fixture.document());
        when(documentNodeRepository.findByDocumentIdAndNodeTypeInOrderByOrderIndexAsc(anyLong(), any()))
                .thenReturn(List.of(section, subsection));
        when(documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(fixture.document().getId(), "subsection"))
                .thenReturn(List.of(subsection));
        when(documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(fixture.document().getId(), "subsection_level2"))
                .thenReturn(List.of());
        when(documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(fixture.document().getId(), "section"))
                .thenReturn(List.of(section));
        when(documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(fixture.document().getId(), "chapter"))
                .thenReturn(List.of());
        when(documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(fixture.document().getId(), "part"))
                .thenReturn(List.of());
        when(documentNodeRepository.findByParentIdOrderByOrderIndexAsc(subsection.getId())).thenReturn(List.of());
        when(documentNodeRepository.findByParentIdOrderByOrderIndexAsc(section.getId())).thenReturn(List.of(subsection));
        when(documentChunkRepository.findByDocumentIdAndNodeIdOrderBySourceOrderAsc(fixture.document().getId(), subsection.getId()))
                .thenReturn(List.of(subsectionChunk));
        when(documentChunkRepository.findByDocumentIdAndNodeIdOrderBySourceOrderAsc(fixture.document().getId(), section.getId()))
                .thenReturn(List.of(sectionChunk));
        when(artifactRepository.findByDocumentNodeIdAndArtifactTypeAndPromptVersionAndModelAndSourceHash(
                anyLong(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(artifactRepository.findLatestCompletedSummaryByNodeId(
                subsection.getId(),
                ragProperties.getEnrichment().getPromptVersion(),
                ragProperties.getAi().getChatModel()
        ))
                .thenReturn(Optional.of(completedSummaryArtifact(fixture.document(), subsection, "Tóm tắt tiểu mục")));
        when(artifactRepository.save(any(DocumentNodeArtifact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.enrichDocument(fixture.document().getId(), false, List.of(DocumentNodeArtifactType.SUMMARY));

        assertThat(modes).containsExactly(
                SummaryMode.SUBSECTION_FROM_CHUNKS,
                SummaryMode.SECTION_FROM_SUBSECTIONS_AND_DIRECT_CHUNKS
        );
        assertThat(fixture.document().getStatus()).isEqualTo(DocumentStatus.READY);
    }

    @Test
    void enrichDocument_summaryOnly_generatesSubsectionLevel2BeforeSubsectionAndSection() {
        Fixture fixture = fixture();
        DocumentNode section = node(fixture.document(), 110L, "section", "Mục 1", null);
        DocumentNode subsection = node(fixture.document(), 111L, "subsection", "Tiểu mục 1", section);
        DocumentNode subsectionLevel2 = node(fixture.document(), 112L, "subsection_level2", "Tiểu mục 1.1", subsection);
        DocumentChunk sectionChunk = chunk(fixture.document(), section, 210L, "Nội dung trực tiếp của mục");
        DocumentChunk subsectionChunk = chunk(fixture.document(), subsection, 211L, "Nội dung trực tiếp của tiểu mục");
        DocumentChunk subsectionLevel2Chunk = chunk(fixture.document(), subsectionLevel2, 212L, "Nội dung tiểu mục cấp hai");

        List<SummaryMode> modes = new ArrayList<>();
        DocumentNodeArtifactGenerator generator = summaryGenerator(modes);
        DocumentEnrichmentService service = service(List.of(generator));
        mockDocument(fixture.document());
        when(documentNodeRepository.findByDocumentIdAndNodeTypeInOrderByOrderIndexAsc(anyLong(), any()))
                .thenReturn(List.of(section, subsection, subsectionLevel2));
        when(documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(fixture.document().getId(), "subsection_level2"))
                .thenReturn(List.of(subsectionLevel2));
        when(documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(fixture.document().getId(), "subsection"))
                .thenReturn(List.of(subsection));
        when(documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(fixture.document().getId(), "section"))
                .thenReturn(List.of(section));
        when(documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(fixture.document().getId(), "chapter"))
                .thenReturn(List.of());
        when(documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(fixture.document().getId(), "part"))
                .thenReturn(List.of());
        when(documentNodeRepository.findByParentIdOrderByOrderIndexAsc(subsection.getId())).thenReturn(List.of(subsectionLevel2));
        when(documentNodeRepository.findByParentIdOrderByOrderIndexAsc(section.getId())).thenReturn(List.of(subsection));
        when(documentChunkRepository.findByDocumentIdAndNodeIdOrderBySourceOrderAsc(fixture.document().getId(), subsectionLevel2.getId()))
                .thenReturn(List.of(subsectionLevel2Chunk));
        when(documentChunkRepository.findByDocumentIdAndNodeIdOrderBySourceOrderAsc(fixture.document().getId(), subsection.getId()))
                .thenReturn(List.of(subsectionChunk));
        when(documentChunkRepository.findByDocumentIdAndNodeIdOrderBySourceOrderAsc(fixture.document().getId(), section.getId()))
                .thenReturn(List.of(sectionChunk));
        when(artifactRepository.findLatestCompletedSummaryByNodeId(
                subsectionLevel2.getId(),
                ragProperties.getEnrichment().getPromptVersion(),
                ragProperties.getAi().getChatModel()
        )).thenReturn(Optional.of(completedSummaryArtifact(fixture.document(), subsectionLevel2, "Tóm tắt cấp hai")));
        when(artifactRepository.findLatestCompletedSummaryByNodeId(
                subsection.getId(),
                ragProperties.getEnrichment().getPromptVersion(),
                ragProperties.getAi().getChatModel()
        )).thenReturn(Optional.of(completedSummaryArtifact(fixture.document(), subsection, "Tóm tắt tiểu mục")));
        when(artifactRepository.findByDocumentNodeIdAndArtifactTypeAndPromptVersionAndModelAndSourceHash(
                anyLong(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(artifactRepository.save(any(DocumentNodeArtifact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.enrichDocument(fixture.document().getId(), false, List.of(DocumentNodeArtifactType.SUMMARY));

        assertThat(modes).containsExactly(
                SummaryMode.SUBSECTION_LEVEL2_FROM_CHUNKS,
                SummaryMode.SUBSECTION_FROM_LEVEL2_AND_DIRECT_CHUNKS,
                SummaryMode.SECTION_FROM_SUBSECTIONS_AND_DIRECT_CHUNKS
        );
        assertThat(fixture.document().getStatus()).isEqualTo(DocumentStatus.READY);
    }

    @Test
    void enrichNode_summary_skipsParentWhenChildSummaryMissing() {
        Fixture fixture = fixture();
        DocumentNode section = DocumentNode.builder()
                .document(fixture.document())
                .subjectId(7L)
                .nodeKey("s1")
                .nodeType("section")
                .title("Mục 1")
                .sectionPath("Chương 1 > Mục 1")
                .orderIndex(1)
                .build();
        section.setId(101L);
        DocumentNode subsection = DocumentNode.builder()
                .document(fixture.document())
                .parent(section)
                .subjectId(7L)
                .nodeKey("ss1")
                .nodeType("subsection")
                .title("Tiểu mục 1")
                .sectionPath("Chương 1 > Mục 1 > Tiểu mục 1")
                .orderIndex(2)
                .build();
        subsection.setId(102L);

        List<SummaryMode> modes = new ArrayList<>();
        DocumentNodeArtifactGenerator generator = summaryGenerator(modes);
        DocumentEnrichmentService service = service(List.of(generator));
        mockDocument(fixture.document());
        when(documentNodeRepository.findById(section.getId())).thenReturn(Optional.of(section));
        when(documentNodeRepository.findByParentIdOrderByOrderIndexAsc(section.getId())).thenReturn(List.of(subsection));
        when(artifactRepository.findLatestCompletedSummaryByNodeId(
                subsection.getId(),
                ragProperties.getEnrichment().getPromptVersion(),
                ragProperties.getAi().getChatModel()
        )).thenReturn(Optional.empty());
        when(artifactRepository.findByDocumentNodeIdAndArtifactTypeAndPromptVersionAndModelAndSourceHash(
                anyLong(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(artifactRepository.save(any(DocumentNodeArtifact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.enrichNode(section.getId(), false, List.of(DocumentNodeArtifactType.SUMMARY));

        ArgumentCaptor<DocumentNodeArtifact> artifactCaptor = ArgumentCaptor.forClass(DocumentNodeArtifact.class);
        verify(artifactRepository).save(artifactCaptor.capture());
        assertThat(artifactCaptor.getValue().getStatus()).isEqualTo(DocumentNodeArtifactStatus.SKIPPED);
        assertThat(artifactCaptor.getValue().getErrorMessage()).contains("Missing completed child summaries");
        assertThat(modes).isEmpty();
    }

    @Test
    void enrichNode_subsectionSummary_usesDirectChunks() {
        Fixture fixture = fixture();
        DocumentNode subsection = node(fixture.document(), 201L, "subsection", "Tiểu mục 1", null);
        DocumentChunk chunk = chunk(fixture.document(), subsection, 301L, "Nội dung tiểu mục");
        List<SummaryGenerationContext> contexts = new ArrayList<>();
        DocumentEnrichmentService service = service(List.of(summaryContextGenerator(contexts)));
        mockDocument(fixture.document());
        when(documentNodeRepository.findById(subsection.getId())).thenReturn(Optional.of(subsection));
        when(documentChunkRepository.findByDocumentIdAndNodeIdOrderBySourceOrderAsc(fixture.document().getId(), subsection.getId()))
                .thenReturn(List.of(chunk));
        when(artifactRepository.findByDocumentNodeIdAndArtifactTypeAndPromptVersionAndModelAndSourceHash(
                anyLong(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(artifactRepository.save(any(DocumentNodeArtifact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.enrichNode(subsection.getId(), false, List.of(DocumentNodeArtifactType.SUMMARY));

        assertThat(contexts).hasSize(1);
        assertThat(contexts.getFirst().summaryMode()).isEqualTo(SummaryMode.SUBSECTION_FROM_CHUNKS);
        assertThat(contexts.getFirst().directChunks()).containsExactly(chunk);
        assertThat(contexts.getFirst().childSummaries()).isEmpty();
    }

    @Test
    void enrichNode_subsectionLevel2Summary_usesDirectChunks() {
        Fixture fixture = fixture();
        DocumentNode subsectionLevel2 = node(fixture.document(), 205L, "subsection_level2", "Tiểu mục cấp hai", null);
        DocumentChunk chunk = chunk(fixture.document(), subsectionLevel2, 305L, "Nội dung tiểu mục cấp hai");
        List<SummaryGenerationContext> contexts = new ArrayList<>();
        DocumentEnrichmentService service = service(List.of(summaryContextGenerator(contexts)));
        mockDocument(fixture.document());
        when(documentNodeRepository.findById(subsectionLevel2.getId())).thenReturn(Optional.of(subsectionLevel2));
        when(documentChunkRepository.findByDocumentIdAndNodeIdOrderBySourceOrderAsc(fixture.document().getId(), subsectionLevel2.getId()))
                .thenReturn(List.of(chunk));
        when(artifactRepository.findByDocumentNodeIdAndArtifactTypeAndPromptVersionAndModelAndSourceHash(
                anyLong(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(artifactRepository.save(any(DocumentNodeArtifact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.enrichNode(subsectionLevel2.getId(), false, List.of(DocumentNodeArtifactType.SUMMARY));

        assertThat(contexts).hasSize(1);
        assertThat(contexts.getFirst().summaryMode()).isEqualTo(SummaryMode.SUBSECTION_LEVEL2_FROM_CHUNKS);
        assertThat(contexts.getFirst().directChunks()).containsExactly(chunk);
    }

    @Test
    void enrichNode_subsectionSummary_usesLevel2SummariesAndDirectChunks() {
        Fixture fixture = fixture();
        DocumentNode subsection = node(fixture.document(), 206L, "subsection", "Tiểu mục 1", null);
        DocumentNode subsectionLevel2 = node(fixture.document(), 207L, "subsection_level2", "Tiểu mục 1.1", subsection);
        DocumentChunk directChunk = chunk(fixture.document(), subsection, 306L, "Nội dung trực tiếp của tiểu mục");
        List<SummaryGenerationContext> contexts = new ArrayList<>();
        DocumentEnrichmentService service = service(List.of(summaryContextGenerator(contexts)));
        mockDocument(fixture.document());
        when(documentNodeRepository.findById(subsection.getId())).thenReturn(Optional.of(subsection));
        when(documentNodeRepository.findByParentIdOrderByOrderIndexAsc(subsection.getId())).thenReturn(List.of(subsectionLevel2));
        when(documentChunkRepository.findByDocumentIdAndNodeIdOrderBySourceOrderAsc(fixture.document().getId(), subsection.getId()))
                .thenReturn(List.of(directChunk));
        when(artifactRepository.findLatestCompletedSummaryByNodeId(
                subsectionLevel2.getId(),
                ragProperties.getEnrichment().getPromptVersion(),
                ragProperties.getAi().getChatModel()
        )).thenReturn(Optional.of(completedSummaryArtifact(fixture.document(), subsectionLevel2, "Tóm tắt cấp hai")));
        when(artifactRepository.findByDocumentNodeIdAndArtifactTypeAndPromptVersionAndModelAndSourceHash(
                anyLong(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(artifactRepository.save(any(DocumentNodeArtifact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.enrichNode(subsection.getId(), false, List.of(DocumentNodeArtifactType.SUMMARY));

        assertThat(contexts).hasSize(1);
        SummaryGenerationContext context = contexts.getFirst();
        assertThat(context.summaryMode()).isEqualTo(SummaryMode.SUBSECTION_FROM_LEVEL2_AND_DIRECT_CHUNKS);
        assertThat(context.childSummaries()).hasSize(1);
        assertThat(context.childSummaries().getFirst().nodeId()).isEqualTo(subsectionLevel2.getId());
        assertThat(context.directChunks()).containsExactly(directChunk);
    }

    @Test
    void enrichNode_emptySummaryInput_persistsSkippedArtifact() {
        Fixture fixture = fixture();
        DocumentNode subsection = node(fixture.document(), 208L, "subsection", "Tiểu mục rỗng", null);
        List<SummaryGenerationContext> contexts = new ArrayList<>();
        DocumentEnrichmentService service = service(List.of(summaryContextGenerator(contexts)));
        mockDocument(fixture.document());
        when(documentNodeRepository.findById(subsection.getId())).thenReturn(Optional.of(subsection));
        when(documentNodeRepository.findByParentIdOrderByOrderIndexAsc(subsection.getId())).thenReturn(List.of());
        when(documentChunkRepository.findByDocumentIdAndNodeIdOrderBySourceOrderAsc(fixture.document().getId(), subsection.getId()))
                .thenReturn(List.of());
        when(nodeScopeService.getScope(subsection.getId()))
                .thenReturn(new DocumentNodeScopeService.NodeScope(subsection, List.of(), "empty-source"));
        when(artifactRepository.findByDocumentNodeIdAndArtifactTypeAndPromptVersionAndModelAndSourceHash(
                anyLong(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(artifactRepository.save(any(DocumentNodeArtifact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.enrichNode(subsection.getId(), false, List.of(DocumentNodeArtifactType.SUMMARY));

        ArgumentCaptor<DocumentNodeArtifact> artifactCaptor = ArgumentCaptor.forClass(DocumentNodeArtifact.class);
        verify(artifactRepository).save(artifactCaptor.capture());
        DocumentNodeArtifact artifact = artifactCaptor.getValue();
        assertThat(artifact.getStatus()).isEqualTo(DocumentNodeArtifactStatus.SKIPPED);
        assertThat(artifact.getErrorMessage()).contains("No chunks available");
        assertThat(artifact.getContentJsonb())
                .containsEntry("summaryMode", SummaryMode.SUBSECTION_FROM_CHUNKS.name());
        assertThat(contexts).isEmpty();
    }

    @Test
    void enrichNode_chapterSummary_usesSectionSummariesAndSkipsRecursiveChunks() {
        Fixture fixture = fixture();
        DocumentNode chapter = node(fixture.document(), 210L, "chapter", "Chương 1", null);
        DocumentNode section = node(fixture.document(), 211L, "section", "Mục 1", chapter);
        List<SummaryGenerationContext> contexts = new ArrayList<>();
        DocumentEnrichmentService service = service(List.of(summaryContextGenerator(contexts)));
        mockDocument(fixture.document());
        when(documentNodeRepository.findById(chapter.getId())).thenReturn(Optional.of(chapter));
        when(documentNodeRepository.findByParentIdOrderByOrderIndexAsc(chapter.getId())).thenReturn(List.of(section));
        when(artifactRepository.findLatestCompletedSummaryByNodeId(
                section.getId(),
                ragProperties.getEnrichment().getPromptVersion(),
                ragProperties.getAi().getChatModel()
        )).thenReturn(Optional.of(completedSummaryArtifact(fixture.document(), section, "Tóm tắt mục")));
        when(artifactRepository.findByDocumentNodeIdAndArtifactTypeAndPromptVersionAndModelAndSourceHash(
                anyLong(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(artifactRepository.save(any(DocumentNodeArtifact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.enrichNode(chapter.getId(), false, List.of(DocumentNodeArtifactType.SUMMARY));

        assertThat(contexts).hasSize(1);
        SummaryGenerationContext context = contexts.getFirst();
        assertThat(context.summaryMode()).isEqualTo(SummaryMode.CHAPTER_FROM_SECTIONS);
        assertThat(context.childSummaries()).hasSize(1);
        assertThat(context.childSummaries().getFirst().nodeId()).isEqualTo(section.getId());
        assertThat(context.directChunks()).isEmpty();
        verify(documentChunkRepository, never()).findByDocumentIdAndNodeIdOrderBySourceOrderAsc(fixture.document().getId(), chapter.getId());
        verify(nodeScopeService, never()).getScope(chapter.getId());
    }

    @Test
    void enrichNode_chapterSummary_prefersCleanedOriginalSummaryNode() {
        Fixture fixture = fixture();
        DocumentNode chapter = node(fixture.document(), 212L, "chapter", "Chương 1", null);
        DocumentNode summaryNode = node(fixture.document(), 213L, "summary", "TÓM TẮT CHƯƠNG 1", chapter);
        DocumentChunk source = chunk(fixture.document(), summaryNode, 312L, "Nội dung tóm tắt gốc đã clean");
        List<SummaryGenerationContext> contexts = new ArrayList<>();
        DocumentEnrichmentService service = service(List.of(summaryContextGenerator(contexts)));
        mockDocument(fixture.document());
        when(documentNodeRepository.findById(chapter.getId())).thenReturn(Optional.of(chapter));
        when(originalSummaryNodeService.findForChapter(chapter))
                .thenReturn(Optional.of(new OriginalSummaryNodeService.OriginalSummary(
                        summaryNode,
                        "Nội dung tóm tắt gốc đã clean",
                        List.of(source),
                        List.of(source),
                        new OriginalSummaryTextCleaner.CleaningStats(100, 32, 1, 1, 1)
                )));
        when(artifactRepository.findByDocumentNodeIdAndArtifactTypeAndPromptVersionAndModelAndSourceHash(
                anyLong(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(artifactRepository.save(any(DocumentNodeArtifact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.enrichNode(chapter.getId(), false, List.of(DocumentNodeArtifactType.SUMMARY));

        assertThat(contexts).hasSize(1);
        SummaryGenerationContext context = contexts.getFirst();
        assertThat(context.summaryMode()).isEqualTo(SummaryMode.CHAPTER_FROM_ORIGINAL_SUMMARY);
        assertThat(context.directChunks()).containsExactly(source);
        assertThat(context.childSummaries()).isEmpty();

        ArgumentCaptor<DocumentNodeArtifact> artifactCaptor = ArgumentCaptor.forClass(DocumentNodeArtifact.class);
        verify(artifactRepository, times(2)).save(artifactCaptor.capture());
        DocumentNodeArtifact completed = artifactCaptor.getAllValues().getLast();
        assertThat(completed.getContentJsonb())
                .containsEntry("sourceMode", "ORIGINAL_SUMMARY_NODE")
                .containsEntry("originalSummaryNodeId", summaryNode.getId())
                .containsEntry("generatedVia", "original_summary_llm");
    }

    @Test
    void enrichNode_partSummary_usesChapterSummaries() {
        Fixture fixture = fixture();
        DocumentNode part = node(fixture.document(), 220L, "part", "Phần I", null);
        DocumentNode chapter = node(fixture.document(), 221L, "chapter", "Chương 1", part);
        List<SummaryGenerationContext> contexts = new ArrayList<>();
        DocumentEnrichmentService service = service(List.of(summaryContextGenerator(contexts)));
        mockDocument(fixture.document());
        when(documentNodeRepository.findById(part.getId())).thenReturn(Optional.of(part));
        when(documentNodeRepository.findByParentIdOrderByOrderIndexAsc(part.getId())).thenReturn(List.of(chapter));
        when(artifactRepository.findLatestCompletedSummaryByNodeId(
                chapter.getId(),
                ragProperties.getEnrichment().getPromptVersion(),
                ragProperties.getAi().getChatModel()
        )).thenReturn(Optional.of(completedSummaryArtifact(fixture.document(), chapter, "Tóm tắt chương")));
        when(artifactRepository.findByDocumentNodeIdAndArtifactTypeAndPromptVersionAndModelAndSourceHash(
                anyLong(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(artifactRepository.save(any(DocumentNodeArtifact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.enrichNode(part.getId(), false, List.of(DocumentNodeArtifactType.SUMMARY));

        assertThat(contexts).hasSize(1);
        assertThat(contexts.getFirst().summaryMode()).isEqualTo(SummaryMode.PART_FROM_CHAPTERS);
        assertThat(contexts.getFirst().childSummaries()).hasSize(1);
        assertThat(contexts.getFirst().childSummaries().getFirst().nodeId()).isEqualTo(chapter.getId());
    }

    @Test
    void enrichNode_chapterSummary_fallsBackToSubsectionSummariesWhenNoSections() {
        Fixture fixture = fixture();
        DocumentNode chapter = node(fixture.document(), 230L, "chapter", "Chương 1", null);
        DocumentNode subsection = node(fixture.document(), 231L, "subsection", "Tiểu mục 1", chapter);
        List<SummaryGenerationContext> contexts = new ArrayList<>();
        DocumentEnrichmentService service = service(List.of(summaryContextGenerator(contexts)));
        mockDocument(fixture.document());
        when(documentNodeRepository.findById(chapter.getId())).thenReturn(Optional.of(chapter));
        when(documentNodeRepository.findByParentIdOrderByOrderIndexAsc(chapter.getId())).thenReturn(List.of(subsection));
        when(artifactRepository.findLatestCompletedSummaryByNodeId(
                subsection.getId(),
                ragProperties.getEnrichment().getPromptVersion(),
                ragProperties.getAi().getChatModel()
        )).thenReturn(Optional.of(completedSummaryArtifact(fixture.document(), subsection, "Tóm tắt tiểu mục")));
        when(artifactRepository.findByDocumentNodeIdAndArtifactTypeAndPromptVersionAndModelAndSourceHash(
                anyLong(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(artifactRepository.save(any(DocumentNodeArtifact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.enrichNode(chapter.getId(), false, List.of(DocumentNodeArtifactType.SUMMARY));

        assertThat(contexts).hasSize(1);
        assertThat(contexts.getFirst().summaryMode()).isEqualTo(SummaryMode.CHAPTER_FALLBACK);
        assertThat(contexts.getFirst().childSummaries()).hasSize(1);
        assertThat(contexts.getFirst().directChunks()).isEmpty();
    }

    private void mockCommonScope(Fixture fixture) {
        mockDocument(fixture.document());
        when(documentNodeRepository.findByDocumentIdAndNodeTypeInOrderByOrderIndexAsc(anyLong(), any()))
                .thenReturn(List.of(fixture.node()));
        when(documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(fixture.document().getId(), "chapter"))
                .thenReturn(List.of(fixture.node()));
        when(documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(fixture.document().getId(), "subsection_level2"))
                .thenReturn(List.of());
        when(documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(fixture.document().getId(), "subsection"))
                .thenReturn(List.of());
        when(documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(fixture.document().getId(), "section"))
                .thenReturn(List.of());
        when(documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(fixture.document().getId(), "part"))
                .thenReturn(List.of());
        when(documentNodeRepository.findByParentIdOrderByOrderIndexAsc(fixture.node().getId()))
                .thenReturn(List.of());
        when(documentChunkRepository.findByDocumentIdAndNodeIdOrderBySourceOrderAsc(fixture.document().getId(), fixture.node().getId()))
                .thenReturn(List.of(fixture.chunk()));
        when(nodeScopeService.getScope(fixture.node().getId()))
                .thenReturn(new DocumentNodeScopeService.NodeScope(
                        fixture.node(),
                        List.of(fixture.chunk()),
                        "source-hash"
                ));
        when(artifactRepository.findByDocumentNodeIdAndArtifactTypeAndPromptVersionAndModelAndSourceHash(
                anyLong(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(artifactRepository.save(any(DocumentNodeArtifact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void mockDocument(Document document) {
        when(documentRepository.findById(document.getId()))
                .thenReturn(Optional.of(document));
        when(documentRepository.save(any(Document.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private DocumentNodeArtifactGenerator summaryGenerator(List<SummaryMode> modes) {
        return new DocumentNodeArtifactGenerator() {
            @Override
            public boolean supports(DocumentNodeArtifactType artifactType) {
                return artifactType == DocumentNodeArtifactType.SUMMARY;
            }

            @Override
            public boolean supportsSummaryMode(SummaryMode summaryMode) {
                return true;
            }

            @Override
            public DocumentNodeArtifactGenerationResult generate(DocumentNodeArtifactGenerationContext context) {
                throw new AssertionError("Legacy generation should not be used for bottom-up summary");
            }

            @Override
            public DocumentNodeArtifactGenerationResult generateSummary(SummaryGenerationContext context) {
                modes.add(context.summaryMode());
                return new DocumentNodeArtifactGenerationResult(
                        Map.of(
                                "summaryMode", context.summaryMode().name(),
                                "summary", "Tóm tắt " + context.node().getTitle(),
                                "generated", true
                        ),
                        42
                );
            }
        };
    }

    private DocumentNodeArtifactGenerator summaryContextGenerator(List<SummaryGenerationContext> contexts) {
        return new DocumentNodeArtifactGenerator() {
            @Override
            public boolean supports(DocumentNodeArtifactType artifactType) {
                return artifactType == DocumentNodeArtifactType.SUMMARY;
            }

            @Override
            public boolean supportsSummaryMode(SummaryMode summaryMode) {
                return true;
            }

            @Override
            public DocumentNodeArtifactGenerationResult generate(DocumentNodeArtifactGenerationContext context) {
                throw new AssertionError("Legacy generation should not be used for bottom-up summary");
            }

            @Override
            public DocumentNodeArtifactGenerationResult generateSummary(SummaryGenerationContext context) {
                contexts.add(context);
                return new DocumentNodeArtifactGenerationResult(
                        Map.of(
                                "summaryMode", context.summaryMode().name(),
                                "summary", "Tóm tắt " + context.node().getTitle(),
                                "generated", true
                        ),
                        42
                );
            }
        };
    }

    private DocumentNodeArtifactGenerator failingSummaryGenerator(List<Long> failingNodeIds) {
        return new DocumentNodeArtifactGenerator() {
            @Override
            public boolean supports(DocumentNodeArtifactType artifactType) {
                return artifactType == DocumentNodeArtifactType.SUMMARY;
            }

            @Override
            public boolean supportsSummaryMode(SummaryMode summaryMode) {
                return true;
            }

            @Override
            public DocumentNodeArtifactGenerationResult generate(DocumentNodeArtifactGenerationContext context) {
                throw new AssertionError("Legacy generation should not be used for bottom-up summary");
            }

            @Override
            public DocumentNodeArtifactGenerationResult generateSummary(SummaryGenerationContext context) {
                if (failingNodeIds.contains(context.node().getId())) {
                    throw new RuntimeException("rate or model failure");
                }
                return new DocumentNodeArtifactGenerationResult(
                        Map.of(
                                "summaryMode", context.summaryMode().name(),
                                "summary", "Tóm tắt " + context.node().getTitle(),
                                "generated", true
                        ),
                        42
                );
            }
        };
    }

    private void mockChapterSummaryBatch(Document document, List<DocumentNode> chapters) {
        when(documentNodeRepository.findByDocumentIdAndNodeTypeInOrderByOrderIndexAsc(anyLong(), any()))
                .thenReturn(chapters);
        when(documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(document.getId(), "subsection_level2"))
                .thenReturn(List.of());
        when(documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(document.getId(), "subsection"))
                .thenReturn(List.of());
        when(documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(document.getId(), "section"))
                .thenReturn(List.of());
        when(documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(document.getId(), "chapter"))
                .thenReturn(chapters);
        when(documentNodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(document.getId(), "part"))
                .thenReturn(List.of());
        for (DocumentNode chapter : chapters) {
            DocumentChunk chunk = chunk(document, chapter, 800L + chapter.getId(), "Nội dung " + chapter.getTitle());
            when(documentNodeRepository.findByParentIdOrderByOrderIndexAsc(chapter.getId())).thenReturn(List.of());
            when(documentChunkRepository.findByDocumentIdAndNodeIdOrderBySourceOrderAsc(document.getId(), chapter.getId()))
                    .thenReturn(List.of(chunk));
            when(nodeScopeService.getScope(chapter.getId()))
                    .thenReturn(new DocumentNodeScopeService.NodeScope(chapter, List.of(chunk), "source-hash-" + chapter.getId()));
        }
        when(artifactRepository.findByDocumentNodeIdAndArtifactTypeAndPromptVersionAndModelAndSourceHash(
                anyLong(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    private List<DocumentNodeArtifact> captureSavedArtifacts() {
        List<DocumentNodeArtifact> savedArtifacts = Collections.synchronizedList(new ArrayList<>());
        when(artifactRepository.save(any(DocumentNodeArtifact.class)))
                .thenAnswer(invocation -> {
                    DocumentNodeArtifact artifact = invocation.getArgument(0);
                    savedArtifacts.add(artifact);
                    return artifact;
                });
        return savedArtifacts;
    }

    private void mockCompletedChapterSummaryLookup(List<DocumentNodeArtifact> savedArtifacts) {
        when(artifactRepository.findByNodeIdsAndArtifactTypeAndStatusOrderByNodeOrderAndUpdatedAt(
                any(),
                eq(DocumentNodeArtifactType.SUMMARY),
                eq(DocumentNodeArtifactStatus.COMPLETED)
        )).thenAnswer(invocation -> savedArtifacts.stream()
                .filter(artifact -> artifact.getArtifactType() == DocumentNodeArtifactType.SUMMARY)
                .filter(artifact -> artifact.getStatus() == DocumentNodeArtifactStatus.COMPLETED)
                .toList());
    }

    private DocumentNodeArtifact completedSummaryArtifact(Document document, DocumentNode node, String summary) {
        DocumentNodeArtifact artifact = DocumentNodeArtifact.builder()
                .document(document)
                .documentNode(node)
                .artifactType(DocumentNodeArtifactType.SUMMARY)
                .status(DocumentNodeArtifactStatus.COMPLETED)
                .promptVersion("enrichment-v2-bottom-up")
                .model("openai-gpt-oss-120b")
                .sourceHash("source-hash-" + node.getId())
                .contentJsonb(Map.of("summary", summary))
                .build();
        artifact.setId(900L + node.getId());
        return artifact;
    }

    private DocumentEnrichmentService service(List<DocumentNodeArtifactGenerator> generators) {
        return new DocumentEnrichmentService(
                documentRepository,
                documentNodeRepository,
                documentChunkRepository,
                artifactRepository,
                nodeScopeService,
                ragProperties,
                objectProvider(generators),
                new TransactionTemplate(new NoOpTransactionManager()),
                mock(RedisTemplate.class),
                quizEnrichmentService,
                reviewQuestionCountResolver,
                aiModelRoutingService,
                originalSummaryNodeService
        );
    }

    private Fixture fixture() {
        Subject subject = Subject.builder().name("Subject").code("SUB").build();
        subject.setId(7L);
        Document document = Document.builder()
                .title("Document")
                .subject(subject)
                .status(DocumentStatus.READY)
                .enrichmentStatus(DocumentEnrichmentStatus.NOT_STARTED)
                .build();
        document.setId(10L);

        DocumentNode node = DocumentNode.builder()
                .document(document)
                .subjectId(subject.getId())
                .nodeKey("n1")
                .nodeType("chapter")
                .title("Chương 1")
                .sectionPath("Chương 1")
                .orderIndex(1)
                .build();
        node.setId(100L);

        DocumentChunk chunk = DocumentChunk.builder()
                .document(document)
                .subjectId(subject.getId())
                .node(node)
                .chunkIndex(1)
                .sourceOrder(1)
                .chunkType("TEXT")
                .content("Nội dung chương 1")
                .build();
        chunk.setId(200L);

        return new Fixture(document, node, chunk);
    }

    private DocumentNode node(Document document, Long id, String nodeType, String title, DocumentNode parent) {
        DocumentNode node = DocumentNode.builder()
                .document(document)
                .parent(parent)
                .subjectId(document.getSubject().getId())
                .nodeKey(nodeType + "-" + id)
                .nodeType(nodeType)
                .title(title)
                .sectionPath(title)
                .orderIndex(id.intValue())
                .build();
        node.setId(id);
        return node;
    }

    private DocumentChunk chunk(Document document, DocumentNode node, Long id, String content) {
        DocumentChunk chunk = DocumentChunk.builder()
                .document(document)
                .subjectId(document.getSubject().getId())
                .node(node)
                .chunkIndex(id.intValue())
                .sourceOrder(id.intValue())
                .chunkType("TEXT")
                .content(content)
                .build();
        chunk.setId(id);
        return chunk;
    }

    private ObjectProvider<DocumentNodeArtifactGenerator> objectProvider(List<DocumentNodeArtifactGenerator> generators) {
        return new ObjectProvider<>() {
            @Override
            public DocumentNodeArtifactGenerator getObject(Object... args) throws BeansException {
                return getObject();
            }

            @Override
            public DocumentNodeArtifactGenerator getIfAvailable() throws BeansException {
                return generators.isEmpty() ? null : generators.getFirst();
            }

            @Override
            public DocumentNodeArtifactGenerator getIfUnique() throws BeansException {
                return generators.size() == 1 ? generators.getFirst() : null;
            }

            @Override
            public DocumentNodeArtifactGenerator getObject() throws BeansException {
                if (generators.isEmpty()) {
                    throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(DocumentNodeArtifactGenerator.class);
                }
                return generators.getFirst();
            }

            @Override
            public Stream<DocumentNodeArtifactGenerator> stream() {
                return generators.stream();
            }

            @Override
            public Stream<DocumentNodeArtifactGenerator> orderedStream() {
                return generators.stream();
            }

            @Override
            public Iterator<DocumentNodeArtifactGenerator> iterator() {
                return generators.iterator();
            }
        };
    }

    private static class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }

    private record Fixture(Document document, DocumentNode node, DocumentChunk chunk) {
    }
}
