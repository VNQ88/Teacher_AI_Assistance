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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VectorRetrievalServiceTest {

    @Mock
    private AiEmbeddingGateway embeddingGateway;

    @Mock
    private DocumentChunkRepository documentChunkRepository;

    private VectorRetrievalService service;

    @BeforeEach
    void setUp() {
        RagProperties ragProperties = new RagProperties();
        ragProperties.setTopK(6);
        ragProperties.setMaxTopK(8);
        ragProperties.setCandidateTopK(24);
        ragProperties.setMinChunkChars(40);
        ragProperties.setEmbeddingDimensions(1024);
        service = new VectorRetrievalService(
                embeddingGateway,
                documentChunkRepository,
                ragProperties,
                new LocalRerankingService()
        );
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
        assertEquals(1, response.getParentGroups().size());
        assertEquals(1, response.getSelectedChunks().size());
        assertEquals(501L, response.getSelectedChunks().getFirst().getChunkId());
        assertThat(response.getPromptContextPreview()).contains("[Source 1]");
        assertThat(response.getPromptContextPreview()).contains("Path: Chương 2 > I. Khái niệm");
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
}
