package com.example.teacherassistantai.service;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.dto.response.RagDebugRetrieveResponse;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.integration.ai.AiEmbeddingGateway;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VectorRetrievalServiceTest {

    @Mock
    private AiEmbeddingGateway embeddingGateway;

    @Mock
    private DocumentChunkRepository documentChunkRepository;

    @Mock
    private RagScopeResolverService ragScopeResolverService;

    @Mock
    private CoarseNodeSearchService coarseNodeSearchService;

    private RagProperties ragProperties;

    private VectorRetrievalService service;

    @BeforeEach
    void setUp() {
        ragProperties = new RagProperties();
        ragProperties.setTopK(6);
        ragProperties.setMaxTopK(8);
        ragProperties.setCandidateTopK(24);
        ragProperties.setMinChunkChars(40);
        ragProperties.setEmbeddingDimensions(1024);
        service = new VectorRetrievalService(
                embeddingGateway,
                documentChunkRepository,
                ragProperties,
                new LocalRerankingService(),
                ragScopeResolverService,
                coarseNodeSearchService
        );
    }

    @Test
    void retrieve_shouldPrefixQueryBeforeEmbedding() {
        when(embeddingGateway.embed(anyString())).thenReturn(vector1024());
        when(documentChunkRepository.searchBySubjectVector(
                anyLong(), anyString(), anyInt(), anyInt(), org.mockito.ArgumentMatchers.<Integer>isNull()))
                .thenReturn(List.of());

        String question = "ATP đóng vai trò gì trong tế bào?";

        service.retrieve(session(), question, 1);

        ArgumentCaptor<String> embeddingInputCaptor = ArgumentCaptor.forClass(String.class);
        verify(embeddingGateway).embed(embeddingInputCaptor.capture());
        assertThat(embeddingInputCaptor.getValue())
                .isEqualTo(new RagProperties().getAi().getQueryInstructionPrefix() + question);
    }

    @Test
    void retrieve_shouldPreferSectionMatchedChunk_andPassSectionFilterToRepository() {
        when(embeddingGateway.embed(anyString())).thenReturn(vector1024());

        DocumentChunk chunkSection3 = DocumentChunk.builder()
                .content("Phan 3 gioi thieu thi hien tai don.")
                .build();
        chunkSection3.setId(101L);

        DocumentChunk chunkSection2 = DocumentChunk.builder()
                .content("Phan 2 dong tu bat quy tac va cach dung trong bai hoc.")
                .build();
        chunkSection2.setId(102L);

        when(documentChunkRepository.searchBySubjectVector(anyLong(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(chunkSection3, chunkSection2));

        ChatSession session = new ChatSession();
        Subject subject = new Subject();
        subject.setId(1L);
        session.setSubject(subject);

        List<DocumentChunk> result = service.retrieve(session, "Phan 2 ve dong tu bat quy tac la gi?", 1);

        assertEquals(1, result.size());
        assertEquals(102L, result.getFirst().getId());

        ArgumentCaptor<Integer> sectionCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(documentChunkRepository).searchBySubjectVector(anyLong(), anyString(), anyInt(), anyInt(), sectionCaptor.capture());
        assertEquals(2, sectionCaptor.getValue());
    }

    @Test
    void retrieve_shouldDetectVietnameseAccentedSectionIntent() {
        when(embeddingGateway.embed(anyString())).thenReturn(vector1024());

        DocumentChunk chunkSection2 = DocumentChunk.builder()
                .content("Chương 2 trình bày khái niệm pháp luật.")
                .sectionPath("Chương 2 > I. Khái niệm")
                .build();
        chunkSection2.setId(112L);

        when(documentChunkRepository.searchBySubjectVector(anyLong(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(chunkSection2));

        List<DocumentChunk> result = service.retrieve(session(), "Chương 2 nói gì?", 1);

        assertEquals(1, result.size());
        assertEquals(112L, result.getFirst().getId());

        ArgumentCaptor<Integer> sectionCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(documentChunkRepository).searchBySubjectVector(anyLong(), anyString(), anyInt(), anyInt(), sectionCaptor.capture());
        assertEquals(2, sectionCaptor.getValue());
    }

    @Test
    void retrieve_shouldExcludeReviewQuestionsForFactualQuestion_whenTextCandidatesExist() {
        when(embeddingGateway.embed(anyString())).thenReturn(vector1024());

        DocumentChunk reviewChunk = chunk(201L, "REVIEW_QUESTIONS",
                "Cau hoi on tap: Trinh bay khai niem nha nuoc?", null, 1, null);
        DocumentChunk textChunk = chunk(202L, "TEXT",
                "Khai niem nha nuoc la mot to chuc quyen luc cong dac biet.", null, 2, null);

        when(documentChunkRepository.searchBySubjectVector(anyLong(), anyString(), anyInt(), anyInt(), org.mockito.ArgumentMatchers.<Integer>isNull()))
                .thenReturn(List.of(reviewChunk, textChunk));

        List<DocumentChunk> result = service.retrieve(session(), "Khái niệm nhà nước là gì?", 1);

        assertEquals(1, result.size());
        assertEquals(202L, result.getFirst().getId());
    }

    @Test
    void retrieve_shouldPreferReviewQuestionChunksForReviewIntent() {
        when(embeddingGateway.embed(anyString())).thenReturn(vector1024());

        DocumentChunk textChunk = chunk(301L, "TEXT",
                "Noi dung ly thuyet ve vat chat va y thuc.", null, 1, null);
        DocumentChunk reviewChunk = chunk(302L, "REVIEW_QUESTIONS",
                "Cau hoi on tap: Phan tich moi quan he giua vat chat va y thuc?", null, 2, null);

        when(documentChunkRepository.searchBySubjectVector(anyLong(), anyString(), anyInt(), anyInt(), org.mockito.ArgumentMatchers.<Integer>isNull()))
                .thenReturn(List.of(textChunk, reviewChunk));

        List<DocumentChunk> result = service.retrieve(session(), "Cho tôi câu hỏi ôn tập về vật chất và ý thức", 1);

        assertEquals(1, result.size());
        assertEquals(302L, result.getFirst().getId());
    }

    @Test
    void retrieve_shouldGroupByParentAndReturnSelectedChildrenInSourceOrder() {
        when(embeddingGateway.embed(anyString())).thenReturn(vector1024());

        DocumentNode parent = DocumentNode.builder().nodeKey("n1").build();
        parent.setId(1L);
        DocumentChunk laterChunk = chunk(401L, "TEXT",
                "Noi dung sau ve chuong 2 va nguyen tac phap luat.", "Chuong 2 > II", 2, parent);
        DocumentChunk earlierChunk = chunk(402L, "TEXT",
                "Noi dung truoc ve chuong 2 va khai niem phap luat.", "Chuong 2 > I", 1, parent);
        DocumentChunk otherParent = chunk(403L, "TEXT",
                "Noi dung ngoai le it lien quan.", "Chuong 9", 3, null);

        when(documentChunkRepository.searchBySubjectVector(anyLong(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(laterChunk, earlierChunk, otherParent));

        List<DocumentChunk> result = service.retrieve(session(), "Chuong 2 phap luat", 2);

        assertEquals(2, result.size());
        assertEquals(402L, result.get(0).getId());
        assertEquals(401L, result.get(1).getId());
    }

    @Test
    void debugRetrieve_shouldReturnCandidatesParentGroupsSelectedChunksAndPromptPreview() {
        when(embeddingGateway.embed(anyString())).thenReturn(vector1024());

        Document document = Document.builder().title("Debug document").build();
        document.setId(9L);
        DocumentNode parent = DocumentNode.builder().nodeKey("n1").build();
        parent.setId(1L);
        DocumentChunk chunk = chunk(501L, "TEXT",
                "Noi dung ve chuong 2 phap luat.", "Chương 2 > I. Khái niệm", 1, parent);
        chunk.setDocument(document);

        when(documentChunkRepository.searchBySubjectVector(anyLong(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(chunk));

        RagDebugRetrieveResponse response = service.debugRetrieve(session(), "Chương 2 pháp luật", 1);

        assertEquals("FACTUAL", response.getIntentType());
        assertEquals(2, response.getSectionNumber());
        assertEquals(1, response.getCandidateCount());
        assertEquals(0, response.getCoarseHitCount());
        assertEquals(1, response.getParentGroups().size());
        assertEquals(1, response.getSelectedChunks().size());
        assertEquals(501L, response.getSelectedChunks().getFirst().getChunkId());
        assertThat(response.getPromptContextPreview()).contains("[Source 1]");
        assertThat(response.getPromptContextPreview()).contains("Path: Chương 2 > I. Khái niệm");
    }

    @Test
    void debugRetrieve_shouldUseScopedVector_whenScopeResolvedWithHighConfidence() {
        when(embeddingGateway.embed(anyString())).thenReturn(vector1024());
        ChatSession session = session();
        String question = "trong chương 3, định lý Pytago được phát biểu thế nào?";
        DocumentNode scopedNode = DocumentNode.builder()
                .nodeKey("chapter-3")
                .nodeType("chapter")
                .title("Chương 3")
                .build();
        scopedNode.setId(42L);
        DocumentChunk chunk = chunk(601L, "TEXT",
                "Dinh ly Pytago phat bieu ve tam giac vuong.", "Chương 3", 1, scopedNode);

        when(ragScopeResolverService.hasExplicitScopeHint(question)).thenReturn(true);
        when(ragScopeResolverService.resolveDeterministicOnly(session, question))
                .thenReturn(ScopeResolution.resolved(scopedNode, 0.92, "test", List.of(scopedNode)));
        when(documentChunkRepository.searchByNodeSubtreeVector(eq(1L), eq(42L), anyString(), eq(40), eq(24)))
                .thenReturn(List.of(chunk));

        RagDebugRetrieveResponse response = service.debugRetrieve(session, question, 4);

        assertEquals("SCOPED_VECTOR", response.getRetrievalMode());
        assertEquals(42L, response.getScopedNodeId());
        assertEquals(1, response.getCandidateCount());
        verify(documentChunkRepository).searchByNodeSubtreeVector(eq(1L), eq(42L), anyString(), eq(40), eq(24));
        verify(documentChunkRepository, never()).searchBySubjectVector(anyLong(), anyString(), anyInt(), anyInt(), org.mockito.ArgumentMatchers.<Integer>any());
        verifyNoInteractions(coarseNodeSearchService);
    }

    @Test
    void retrieve_shouldFallbackFlat_whenNoScopeKeyword() {
        when(embeddingGateway.embed(anyString())).thenReturn(vector1024());
        String question = "Định lý Pytago là gì?";
        DocumentChunk chunk = chunk(701L, "TEXT",
                "Dinh ly Pytago la he thuc trong tam giac vuong.", null, 1, null);

        when(ragScopeResolverService.hasExplicitScopeHint(question)).thenReturn(false);
        when(documentChunkRepository.searchBySubjectVector(anyLong(), anyString(), anyInt(), anyInt(), org.mockito.ArgumentMatchers.<Integer>isNull()))
                .thenReturn(List.of(chunk));

        List<DocumentChunk> result = service.retrieve(session(), question, 1);

        assertEquals(1, result.size());
        assertEquals(701L, result.getFirst().getId());
        verify(ragScopeResolverService, never()).resolveDeterministicOnly(org.mockito.ArgumentMatchers.any(), anyString());
        verify(documentChunkRepository, never()).searchByNodeSubtreeVector(anyLong(), anyLong(), anyString(), anyInt(), anyInt());
    }

    @Test
    void retrieve_shouldFallbackFlat_whenScopeResolutionAmbiguous() {
        when(embeddingGateway.embed(anyString())).thenReturn(vector1024());
        ChatSession session = session();
        String question = "trong chương 3 nói gì?";
        DocumentChunk chunk = chunk(801L, "TEXT",
                "Noi dung chuong 3.", "Chương 3", 1, null);

        when(ragScopeResolverService.hasExplicitScopeHint(question)).thenReturn(true);
        when(ragScopeResolverService.resolveDeterministicOnly(session, question))
                .thenReturn(ScopeResolution.ambiguous("ambiguous", List.of()));
        when(documentChunkRepository.searchBySubjectVector(anyLong(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(chunk));

        List<DocumentChunk> result = service.retrieve(session, question, 1);

        assertEquals(1, result.size());
        assertEquals(801L, result.getFirst().getId());
        verify(documentChunkRepository, never()).searchByNodeSubtreeVector(anyLong(), anyLong(), anyString(), anyInt(), anyInt());
    }

    @Test
    void retrieve_shouldFallbackFlat_whenScopeConfidenceTooLow() {
        when(embeddingGateway.embed(anyString())).thenReturn(vector1024());
        ChatSession session = session();
        String question = "trong chương 3 nói gì?";
        DocumentNode scopedNode = DocumentNode.builder().nodeKey("chapter-3").build();
        scopedNode.setId(42L);
        DocumentChunk chunk = chunk(901L, "TEXT",
                "Noi dung chuong 3.", "Chương 3", 1, null);

        when(ragScopeResolverService.hasExplicitScopeHint(question)).thenReturn(true);
        when(ragScopeResolverService.resolveDeterministicOnly(session, question))
                .thenReturn(ScopeResolution.resolved(scopedNode, 0.70, "test", List.of(scopedNode)));
        when(documentChunkRepository.searchBySubjectVector(anyLong(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(chunk));

        List<DocumentChunk> result = service.retrieve(session, question, 1);

        assertEquals(1, result.size());
        assertEquals(901L, result.getFirst().getId());
        verify(documentChunkRepository, never()).searchByNodeSubtreeVector(anyLong(), anyLong(), anyString(), anyInt(), anyInt());
    }

    @Test
    void retrieve_shouldSkipScopeResolver_whenScopedVectorDisabled() {
        ragProperties.getRetrieval().getScopedVector().setEnabled(false);
        when(embeddingGateway.embed(anyString())).thenReturn(vector1024());
        when(documentChunkRepository.searchBySubjectVector(
                anyLong(), anyString(), anyInt(), anyInt(), eq(3)))
                .thenReturn(List.of());

        service.retrieve(session(), "trong chương 3 nói gì?", 1);

        verifyNoInteractions(ragScopeResolverService);
        verify(documentChunkRepository, never()).searchByNodeSubtreeVector(anyLong(), anyLong(), anyString(), anyInt(), anyInt());
    }

    @Test
    void debugRetrieve_shouldFallbackFlatAndReuseEmbedding_whenScopedCandidatesEmpty() {
        when(embeddingGateway.embed(anyString())).thenReturn(vector1024());
        ChatSession session = session();
        String question = "trong chương 3 nói gì?";
        DocumentNode scopedNode = DocumentNode.builder().nodeKey("chapter-3").build();
        scopedNode.setId(42L);
        DocumentChunk flatChunk = chunk(1001L, "TEXT",
                "Noi dung fallback ve chuong 3.", "Chương 3", 1, null);

        when(ragScopeResolverService.hasExplicitScopeHint(question)).thenReturn(true);
        when(ragScopeResolverService.resolveDeterministicOnly(session, question))
                .thenReturn(ScopeResolution.resolved(scopedNode, 0.92, "test", List.of(scopedNode)));
        when(documentChunkRepository.searchByNodeSubtreeVector(eq(1L), eq(42L), anyString(), eq(40), eq(24)))
                .thenReturn(List.of());
        when(documentChunkRepository.searchBySubjectVector(eq(1L), anyString(), eq(40), eq(24), eq(3)))
                .thenReturn(List.of(flatChunk));

        RagDebugRetrieveResponse response = service.debugRetrieve(session, question, 1);

        assertEquals("SCOPED_EMPTY_FALLBACK", response.getRetrievalMode());
        assertEquals(42L, response.getScopedNodeId());
        assertEquals(1, response.getCandidateCount());
        verify(embeddingGateway, times(1)).embed(anyString());
        verify(documentChunkRepository).searchByNodeSubtreeVector(eq(1L), eq(42L), anyString(), eq(40), eq(24));
        verify(documentChunkRepository).searchBySubjectVector(eq(1L), anyString(), eq(40), eq(24), eq(3));
    }

    @Test
    void debugRetrieve_shouldUseFlatVector_whenCoarseToFineDisabled() {
        ragProperties.getRetrieval().getCoarseToFine().setEnabled(false);
        when(embeddingGateway.embed(anyString())).thenReturn(vector1024());
        DocumentChunk chunk = chunk(1101L, "TEXT",
                "Khai niem nha nuoc la to chuc quyen luc cong.", null, 1, null);
        when(documentChunkRepository.searchBySubjectVector(
                eq(1L), anyString(), eq(40), eq(24), org.mockito.ArgumentMatchers.<Integer>isNull()))
                .thenReturn(List.of(chunk));

        RagDebugRetrieveResponse response = service.debugRetrieve(session(), "Khái niệm nhà nước là gì?", 1);

        assertEquals("FLAT_VECTOR", response.getRetrievalMode());
        assertEquals(1, response.getSelectedCount());
        assertEquals(0, response.getCoarseHitCount());
        assertThat(response.getFallbackReason()).isNull();
        verifyNoInteractions(coarseNodeSearchService);
    }

    @Test
    void debugRetrieve_shouldFallbackFlat_whenCoarseHasNoHits() {
        when(embeddingGateway.embed(anyString())).thenReturn(vector1024());
        when(coarseNodeSearchService.search(eq(1L), anyString())).thenReturn(List.of());
        DocumentChunk chunk = chunk(1201L, "TEXT",
                "Khai niem phap luat la he thong quy tac xu su.", null, 1, null);
        when(documentChunkRepository.searchBySubjectVector(
                eq(1L), anyString(), eq(40), eq(24), org.mockito.ArgumentMatchers.<Integer>isNull()))
                .thenReturn(List.of(chunk));

        RagDebugRetrieveResponse response = service.debugRetrieve(session(), "Khái niệm pháp luật là gì?", 1);

        assertEquals("COARSE_TO_FINE_EMPTY_FALLBACK", response.getRetrievalMode());
        assertEquals("NO_COARSE_HITS", response.getFallbackReason());
        assertEquals(0, response.getCoarseHitCount());
        assertEquals(0, response.getFineCandidateCount());
        assertEquals(0, response.getFlatGuardrailCandidateCount());
        assertEquals(1, response.getSelectedCount());
    }

    @Test
    void debugRetrieve_shouldFallbackFlat_whenCoarseHitsButFineCandidatesEmpty() {
        when(embeddingGateway.embed(anyString())).thenReturn(vector1024());
        when(coarseNodeSearchService.search(eq(1L), anyString()))
                .thenReturn(List.of(coarseHit(10L, 42L, 9L, "Document", "section", "Chương 1 > Mục 1", 0.12)));
        when(documentChunkRepository.searchByNodeSubtreeVector(eq(1L), eq(42L), anyString(), eq(40), eq(8)))
                .thenReturn(List.of());
        DocumentChunk flatChunk = chunk(1301L, "TEXT",
                "Noi dung fallback flat.", null, 1, null);
        when(documentChunkRepository.searchBySubjectVector(
                eq(1L), anyString(), eq(40), eq(24), org.mockito.ArgumentMatchers.<Integer>isNull()))
                .thenReturn(List.of(flatChunk));

        RagDebugRetrieveResponse response = service.debugRetrieve(session(), "Nội dung chính là gì?", 1);

        assertEquals("COARSE_TO_FINE_INSUFFICIENT_FALLBACK", response.getRetrievalMode());
        assertEquals("NO_FINE_CANDIDATES", response.getFallbackReason());
        assertEquals(1, response.getCoarseHitCount());
        assertEquals(42L, response.getCoarseHits().getFirst().getNodeId());
        assertEquals(0, response.getFineCandidateCount());
        assertEquals(0, response.getFlatGuardrailCandidateCount());
        assertEquals(1, response.getSelectedCount());
    }

    @Test
    void debugRetrieve_shouldUseCoarseToFineAndDeduplicateFlatGuardrailCandidates() {
        when(embeddingGateway.embed(anyString())).thenReturn(vector1024());
        when(coarseNodeSearchService.search(eq(1L), anyString()))
                .thenReturn(List.of(coarseHit(11L, 43L, 9L, "Document", "section", "Chương 2 > I", 0.09)));

        DocumentNode parent = DocumentNode.builder().nodeKey("section-43").build();
        parent.setId(43L);
        DocumentChunk fineChunk = chunk(1401L, "TEXT",
                "Khai niem nha nuoc la to chuc quyen luc cong dac biet.", "Chương 2 > I", 1, parent);
        DocumentChunk guardrailChunk = chunk(1402L, "TEXT",
                "Nha nuoc co chu quyen va bo may quan ly xa hoi.", "Chương 2 > II", 2, parent);
        when(documentChunkRepository.searchByNodeSubtreeVector(eq(1L), eq(43L), anyString(), eq(40), eq(8)))
                .thenReturn(List.of(fineChunk));
        when(documentChunkRepository.searchBySubjectVector(
                eq(1L), anyString(), eq(40), eq(8), org.mockito.ArgumentMatchers.<Integer>isNull()))
                .thenReturn(List.of(fineChunk, guardrailChunk));

        RagDebugRetrieveResponse response = service.debugRetrieve(session(), "Khái niệm nhà nước là gì?", 4);

        assertEquals("COARSE_TO_FINE_VECTOR", response.getRetrievalMode());
        assertThat(response.getFallbackReason()).isNull();
        assertEquals(1, response.getCoarseHitCount());
        assertEquals(1, response.getFineCandidateCount());
        assertEquals(2, response.getFlatGuardrailCandidateCount());
        assertEquals(2, response.getCandidateCount());
        assertEquals(2, response.getSelectedCount());
        assertEquals(43L, response.getCoarseHits().getFirst().getNodeId());
        verify(documentChunkRepository, never()).searchBySubjectVector(
                eq(1L), anyString(), eq(40), eq(24), org.mockito.ArgumentMatchers.<Integer>isNull());
    }

    private List<Double> vector1024() {
        return IntStream.range(0, 1024)
                .mapToObj(i -> 0.01d)
                .toList();
    }

    private ChatSession session() {
        ChatSession session = new ChatSession();
        Subject subject = new Subject();
        subject.setId(1L);
        session.setSubject(subject);
        return session;
    }

    private DocumentChunk chunk(Long id,
                                String chunkType,
                                String content,
                                String sectionPath,
                                Integer sourceOrder,
                                DocumentNode parentNode) {
        DocumentChunk chunk = DocumentChunk.builder()
                .chunkType(chunkType)
                .content(content)
                .sectionPath(sectionPath)
                .sourceOrder(sourceOrder)
                .chunkIndex(sourceOrder)
                .parentNode(parentNode)
                .build();
        chunk.setId(id);
        return chunk;
    }

    private DocumentNodeArtifactRepository.CoarseNodeHit coarseHit(Long artifactId,
                                                                   Long nodeId,
                                                                   Long documentId,
                                                                   String documentTitle,
                                                                   String nodeType,
                                                                   String sectionPath,
                                                                   Double distance) {
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
                return documentId;
            }

            @Override
            public String getDocumentTitle() {
                return documentTitle;
            }

            @Override
            public String getNodeType() {
                return nodeType;
            }

            @Override
            public String getSectionPath() {
                return sectionPath;
            }

            @Override
            public Double getDistance() {
                return distance;
            }
        };
    }
}
