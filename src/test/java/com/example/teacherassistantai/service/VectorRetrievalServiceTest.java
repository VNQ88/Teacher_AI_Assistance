package com.example.teacherassistantai.service;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.integration.gemini.GeminiEmbeddingGateway;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VectorRetrievalServiceTest {

    @Mock
    private GeminiEmbeddingGateway embeddingGateway;

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
        ragProperties.setEmbeddingDimensions(3072);
        service = new VectorRetrievalService(embeddingGateway, documentChunkRepository, ragProperties);
    }

    @Test
    void retrieve_shouldPreferSectionMatchedChunk_andPassSectionFilterToRepository() {
        when(embeddingGateway.embed(anyString())).thenReturn(vector3072());

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

    private List<Double> vector3072() {
        return IntStream.range(0, 3072)
                .mapToObj(i -> 0.01d)
                .toList();
    }
}



