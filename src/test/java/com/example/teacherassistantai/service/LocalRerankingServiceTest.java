package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalRerankingServiceTest {

    private final LocalRerankingService service = new LocalRerankingService();

    @Test
    void rerank_shouldPreferReviewQuestionChunksForReviewIntent() {
        LocalRerankingService.RetrievalIntent intent =
                service.detectIntent("Cho tôi câu hỏi ôn tập về vật chất và ý thức");

        DocumentChunk textChunk = chunk(1L, "TEXT",
                "Noi dung ly thuyet ve vat chat va y thuc.", null, 1, null);
        DocumentChunk reviewChunk = chunk(2L, "REVIEW_QUESTIONS",
                "Cau hoi on tap: Phan tich moi quan he giua vat chat va y thuc?", null, 2, null);

        LocalRerankingService.RerankResult result =
                service.rerank("Cho tôi câu hỏi ôn tập về vật chất và ý thức", List.of(textChunk, reviewChunk), intent, 1);

        assertThat(result.selected()).extracting(DocumentChunk::getId).containsExactly(2L);
    }

    @Test
    void rerank_shouldSelectChildrenFromBestParentInSourceOrder() {
        LocalRerankingService.RetrievalIntent intent = service.detectIntent("Chuong 2 phap luat");

        DocumentNode parent = DocumentNode.builder().nodeKey("n1").build();
        parent.setId(1L);
        DocumentChunk laterChunk = chunk(11L, "TEXT",
                "Noi dung sau ve chuong 2 va nguyen tac phap luat.", "Chuong 2 > II", 2, parent);
        DocumentChunk earlierChunk = chunk(12L, "TEXT",
                "Noi dung truoc ve chuong 2 va khai niem phap luat.", "Chuong 2 > I", 1, parent);
        DocumentChunk otherParent = chunk(13L, "TEXT",
                "Noi dung ngoai le it lien quan.", "Chuong 9", 3, null);

        LocalRerankingService.RerankResult result =
                service.rerank("Chuong 2 phap luat", List.of(laterChunk, earlierChunk, otherParent), intent, 2);

        assertThat(result.selected()).extracting(DocumentChunk::getId).containsExactly(12L, 11L);
        assertThat(result.parentGroups()).hasSize(2);
    }

    @Test
    void detectIntent_shouldIdentifyVietnameseSectionNumber() {
        LocalRerankingService.RetrievalIntent intent = service.detectIntent("Chương 2 nói gì?");

        assertThat(intent.type()).isEqualTo(LocalRerankingService.RetrievalIntentType.FACTUAL);
        assertThat(intent.sectionNumber()).isEqualTo(2);
    }

    @Test
    void rerank_shouldExcludeCitationChunksWhenContentCandidatesExist() {
        LocalRerankingService.RetrievalIntent intent =
                service.detectIntent("Hồ Chí Minh nói gì về lý luận cách mạng?");

        DocumentChunk citationChunk = chunk(21L, "CITATION",
                "3 Hồ Chí Minh Toàn tập, Nxb Chính trị quốc gia, Hà Nội, 2011, tập 5, trang 273.",
                null, 1, null);
        DocumentChunk textChunk = chunk(22L, "TEXT",
                "Hồ Chí Minh nhấn mạnh lý luận cách mạng phải gắn với thực tiễn.",
                null, 2, null);

        LocalRerankingService.RerankResult result =
                service.rerank("Hồ Chí Minh nói gì về lý luận cách mạng?",
                        List.of(citationChunk, textChunk), intent, 1);

        assertThat(result.policyCandidates()).extracting(scored -> scored.chunk().getId())
                .containsExactly(22L);
        assertThat(result.selected()).extracting(DocumentChunk::getId).containsExactly(22L);
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
