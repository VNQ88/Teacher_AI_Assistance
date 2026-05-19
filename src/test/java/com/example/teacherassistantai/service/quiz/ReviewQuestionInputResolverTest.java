package com.example.teacherassistantai.service.quiz;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.integration.ai.AiModelRoutingService;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import com.example.teacherassistantai.service.DocumentNodeScopeService;
import com.example.teacherassistantai.service.ReviewQuestionCountResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewQuestionInputResolverTest {

    @Test
    void resolve_chapterUsesChildSummariesFallbackChunksAndRepresentativeChunks() {
        Fixture fixture = fixture();
        ReviewQuestionInputResolver resolver = resolver(fixture);
        DocumentNode chapter = node(100L, "chapter", "Chương 1", fixture.document);
        DocumentNode section1 = node(201L, "section", "1.1", fixture.document);
        DocumentNode section2 = node(202L, "section", "1.2", fixture.document);
        DocumentChunk citationChunk = chunk(301L, section1, 1, "Citation source");
        DocumentChunk section1Representative = chunk(302L, section1, 2, "Representative 1");
        DocumentChunk section2Fallback = chunk(303L, section2, 3, "Fallback 2");
        DocumentChunk section2Representative = chunk(304L, section2, 4, "Representative 2");

        when(fixture.nodeRepository.findByParentIdOrderByOrderIndexAsc(100L))
                .thenReturn(List.of(section1, section2));
        when(fixture.artifactRepository.findLatestCompletedSummaryByNodeId(201L, "enrichment-v1", "summary-model"))
                .thenReturn(Optional.of(summaryArtifact(section1, 901L, "hash-1", "Tóm tắt section 1", citationChunk.getId())));
        when(fixture.artifactRepository.findLatestCompletedSummaryByNodeId(202L, "enrichment-v1", "summary-model"))
                .thenReturn(Optional.empty());
        when(fixture.chunkRepository.findAllById(any())).thenReturn(List.of(citationChunk));
        when(fixture.nodeScopeService.getScope(201L))
                .thenReturn(new DocumentNodeScopeService.NodeScope(section1, List.of(section1Representative), "scope-1"));
        when(fixture.nodeScopeService.getScope(202L))
                .thenReturn(new DocumentNodeScopeService.NodeScope(section2, List.of(section2Fallback, section2Representative), "scope-2"));

        ReviewQuestionGenerationContext context = resolver.resolve(chapter);

        assertThat(context.inputMode()).isEqualTo(QuizInputMode.MIXED_CHILD_SUMMARIES_AND_REPRESENTATIVE_CHUNKS);
        assertThat(context.childSummaries()).hasSize(1);
        assertThat(context.fallbackRawChunks()).containsOnlyKeys(202L);
        assertThat(context.representativeChildChunks()).containsOnlyKeys(201L, 202L);
        assertThat(context.allowedCitationChunks()).extracting(DocumentChunk::getId)
                .containsExactly(301L, 302L, 303L, 304L);
        assertThat(context.summaryBasedTargetCount()).isEqualTo(12);
        assertThat(context.representativeTargetCount()).isEqualTo(13);
        assertThat(context.coverage().expectedChildCount()).isEqualTo(2);
        assertThat(context.coverage().usedChildSummaryCount()).isEqualTo(1);
        assertThat(context.coverage().fallbackChildCount()).isEqualTo(1);
    }

    @Test
    void resolve_leafFallsBackToRawChunks() {
        Fixture fixture = fixture();
        ReviewQuestionInputResolver resolver = resolver(fixture);
        DocumentNode leaf = node(300L, "subsection_level2", "1.1.1", fixture.document);
        DocumentChunk raw = chunk(401L, leaf, 1, "Raw content");

        when(fixture.nodeScopeService.getScope(300L))
                .thenReturn(new DocumentNodeScopeService.NodeScope(leaf, List.of(raw), "raw-scope"));

        ReviewQuestionGenerationContext context = resolver.resolve(leaf);

        assertThat(context.inputMode()).isEqualTo(QuizInputMode.RAW_CHUNKS);
        assertThat(context.rawChunks()).containsExactly(raw);
        assertThat(context.allowedCitationChunks()).containsExactly(raw);
        assertThat(context.summaryBasedTargetCount()).isEqualTo(10);
        assertThat(context.representativeTargetCount()).isZero();
    }

    private ReviewQuestionInputResolver resolver(Fixture fixture) {
        return new ReviewQuestionInputResolver(
                fixture.nodeRepository,
                fixture.artifactRepository,
                fixture.chunkRepository,
                fixture.nodeScopeService,
                fixture.ragProperties,
                new ReviewQuestionCountResolver(fixture.ragProperties),
                fixture.aiModelRoutingService
        );
    }

    private Fixture fixture() {
        Document document = Document.builder().title("Giáo trình").build();
        document.setId(10L);
        RagProperties ragProperties = new RagProperties();
        AiModelRoutingService aiModelRoutingService = mock(AiModelRoutingService.class);
        when(aiModelRoutingService.enrichmentModelFor(DocumentNodeArtifactType.SUMMARY)).thenReturn("summary-model");
        return new Fixture(
                document,
                mock(DocumentNodeRepository.class),
                mock(DocumentNodeArtifactRepository.class),
                mock(DocumentChunkRepository.class),
                mock(DocumentNodeScopeService.class),
                ragProperties,
                aiModelRoutingService
        );
    }

    private DocumentNode node(Long id, String nodeType, String title, Document document) {
        DocumentNode node = DocumentNode.builder()
                .document(document)
                .nodeType(nodeType)
                .title(title)
                .sectionPath(title)
                .orderIndex(id.intValue())
                .build();
        node.setId(id);
        return node;
    }

    private DocumentChunk chunk(Long id, DocumentNode node, int sourceOrder, String content) {
        DocumentChunk chunk = DocumentChunk.builder()
                .document(node.getDocument())
                .node(node)
                .sourceOrder(sourceOrder)
                .chunkIndex(sourceOrder)
                .content(content)
                .sectionPath(node.getSectionPath())
                .build();
        chunk.setId(id);
        return chunk;
    }

    private DocumentNodeArtifact summaryArtifact(DocumentNode node,
                                                 Long id,
                                                 String sourceHash,
                                                 String summary,
                                                 Long chunkId) {
        DocumentNodeArtifact artifact = DocumentNodeArtifact.builder()
                .document(node.getDocument())
                .documentNode(node)
                .artifactType(DocumentNodeArtifactType.SUMMARY)
                .sourceHash(sourceHash)
                .contentJsonb(Map.of(
                        "summary", summary,
                        "citations", List.of(Map.of("chunkId", chunkId))
                ))
                .build();
        artifact.setId(id);
        return artifact;
    }

    private record Fixture(
            Document document,
            DocumentNodeRepository nodeRepository,
            DocumentNodeArtifactRepository artifactRepository,
            DocumentChunkRepository chunkRepository,
            DocumentNodeScopeService nodeScopeService,
            RagProperties ragProperties,
            AiModelRoutingService aiModelRoutingService
    ) {
    }
}
