package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.ChatSessionType;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagScopeResolverServiceTest {

    @Test
    void hasExplicitScopeHint_detectsScopedQuestionsOnly() {
        RagScopeResolverService resolverService = new RagScopeResolverService(mock(DocumentNodeRepository.class));

        assertThat(resolverService.hasExplicitScopeHint("trong chương 3 nói gì")).isTrue();
        assertThat(resolverService.hasExplicitScopeHint("chương III nói gì")).isTrue();
        assertThat(resolverService.hasExplicitScopeHint("mục 2.1 là gì")).isTrue();
        assertThat(resolverService.hasExplicitScopeHint("định lý 2.1 là gì")).isFalse();
        assertThat(resolverService.hasExplicitScopeHint("mục tiêu 2 là gì")).isFalse();
    }

    @Test
    void resolve_matchesExplicitChapterNumber() {
        DocumentNodeRepository repository = mock(DocumentNodeRepository.class);
        RagScopeResolverService resolverService = new RagScopeResolverService(repository);
        ChatSession session = session();
        DocumentNode chapterOne = node(100L, "chapter", "Chương 1: Mở đầu", "Chương 1", 1);
        DocumentNode chapterTwo = node(101L, "chapter", "Chương 2: Chủ nghĩa duy vật", "Chương 2", 2);

        when(repository.findBySubjectIdAndNodeTypeInOrderByOrderIndexAsc(eq(7L), any()))
                .thenReturn(List.of(chapterOne, chapterTwo));

        Optional<DocumentNode> resolved = resolverService.resolve(session, "Tóm tắt Chương 2");

        assertThat(resolved).contains(chapterTwo);
    }

    @Test
    void resolve_matchesRomanChapterNumber() {
        DocumentNodeRepository repository = mock(DocumentNodeRepository.class);
        RagScopeResolverService resolverService = new RagScopeResolverService(repository);
        ChatSession session = session();
        DocumentNode chapterTwo = node(101L, "chapter", "Chương 2: Chủ nghĩa duy vật", "Chương 2", 2);

        when(repository.findBySubjectIdAndNodeTypeInOrderByOrderIndexAsc(eq(7L), any()))
                .thenReturn(List.of(chapterTwo));

        Optional<DocumentNode> resolved = resolverService.resolve(session, "Tóm tắt chương II");

        assertThat(resolved).contains(chapterTwo);
    }

    @Test
    void resolveDetailed_matchesChapterRomanTitleWhenAskedWithArabicNumber() {
        DocumentNodeRepository repository = mock(DocumentNodeRepository.class);
        RagScopeResolverService resolverService = new RagScopeResolverService(repository);
        ChatSession session = session();
        DocumentNode chapterFive = node(105L, "chapter", "Chương V: Chủ nghĩa duy vật lịch sử", "Chương V", 5);

        when(repository.findBySubjectIdAndNodeTypeInOrderByOrderIndexAsc(eq(7L), any()))
                .thenReturn(List.of(chapterFive));

        ScopeResolution resolved = resolverService.resolveDetailed(session, "Tóm tắt chương 5");

        assertThat(resolved.status()).isEqualTo(ScopeResolution.Status.RESOLVED);
        assertThat(resolved.node()).isEqualTo(chapterFive);
    }

    @Test
    void resolveDetailed_explicitChapterDoesNotFallbackToChildPathMatch() {
        DocumentNodeRepository repository = mock(DocumentNodeRepository.class);
        RagScopeResolverService resolverService = new RagScopeResolverService(repository);
        ChatSession session = session();
        DocumentNode chapterFour = node(104L, "chapter",
                "Chương IV Một số trào lưu triết học phương Tây hiện đại",
                "Chương IV Một số trào lưu triết học phương Tây hiện đại", 4);
        DocumentNode child = node(204L, "subsection",
                "4. Chủ nghĩa Tôma mới",
                "Chương IV Một số trào lưu triết học phương Tây hiện đại > 4. Chủ nghĩa Tôma mới", 44);

        when(repository.findBySubjectIdAndNodeTypeInOrderByOrderIndexAsc(eq(7L), any()))
                .thenReturn(List.of(chapterFour, child));

        ScopeResolution resolved = resolverService.resolveDetailed(session, "Tóm tắt chương V");

        assertThat(resolved.status()).isEqualTo(ScopeResolution.Status.NOT_FOUND);
    }

    @Test
    void resolveDetailed_explicitChapterNumberIgnoresBareParentNumberInSectionPath() {
        DocumentNodeRepository repository = mock(DocumentNodeRepository.class);
        RagScopeResolverService resolverService = new RagScopeResolverService(repository);
        ChatSession session = session();
        DocumentNode chapterTwo = node(102L, "chapter",
                "Chương II: Nội dung khác",
                "Phần I > Chương II: Nội dung khác", 2);

        when(repository.findBySubjectIdAndNodeTypeInOrderByOrderIndexAsc(eq(7L), any()))
                .thenReturn(List.of(chapterTwo));

        ScopeResolution resolved = resolverService.resolveDetailed(session, "Tóm tắt Chương I");

        assertThat(resolved.status()).isEqualTo(ScopeResolution.Status.NOT_FOUND);
    }

    @Test
    void resolveDetailed_titlePhraseExactChapterBeatsChildPathMatch() {
        DocumentNodeRepository repository = mock(DocumentNodeRepository.class);
        RagScopeResolverService resolverService = new RagScopeResolverService(repository);
        ChatSession session = session();
        DocumentNode chapter = node(104L, "chapter",
                "Chương IV Một số trào lưu triết học phương Tây hiện đại",
                "Chương IV Một số trào lưu triết học phương Tây hiện đại", 4);
        DocumentNode child = node(204L, "subsection",
                "4. Chủ nghĩa Tôma mới",
                "Chương IV Một số trào lưu triết học phương Tây hiện đại > 4. Chủ nghĩa Tôma mới", 44);

        when(repository.findBySubjectIdAndNodeTypeInOrderByOrderIndexAsc(eq(7L), any()))
                .thenReturn(List.of(child, chapter));

        ScopeResolution resolved = resolverService.resolveDetailed(
                session, "Tóm tắt Một số trào lưu triết học phương Tây hiện đại");

        assertThat(resolved.status()).isEqualTo(ScopeResolution.Status.RESOLVED);
        assertThat(resolved.node()).isEqualTo(chapter);
    }

    @Test
    void resolveDetailed_wholeDocumentKeywordResolvesDocumentRoot() {
        DocumentNodeRepository repository = mock(DocumentNodeRepository.class);
        RagScopeResolverService resolverService = new RagScopeResolverService(repository);
        ChatSession session = session();
        DocumentNode documentRoot = node(10L, "document", "Giáo trình Triết học", "Giáo trình Triết học", 0);

        when(repository.findBySubjectIdAndNodeTypeInOrderByOrderIndexAsc(eq(7L), any()))
                .thenReturn(List.of(documentRoot));

        ScopeResolution resolved = resolverService.resolveDetailed(session, "Tóm tắt toàn bộ tài liệu");

        assertThat(resolved.status()).isEqualTo(ScopeResolution.Status.RESOLVED);
        assertThat(resolved.node()).isEqualTo(documentRoot);
    }

    @Test
    void resolveDetailed_wholeDocumentVariantsResolveDocumentRootWithoutLlmFallback() {
        DocumentNodeRepository repository = mock(DocumentNodeRepository.class);
        LlmScopeDisambiguationService llm = mock(LlmScopeDisambiguationService.class);
        RagScopeResolverService resolverService = new RagScopeResolverService(repository, llm);
        ChatSession session = session();
        DocumentNode documentRoot = node(10L, "document", "Giáo trình Triết học", "Giáo trình Triết học", 0);
        DocumentNode chapterOne = node(101L, "chapter", "Chương 1", "Chương 1", 1);

        when(repository.findBySubjectIdAndNodeTypeInOrderByOrderIndexAsc(eq(7L), any()))
                .thenReturn(List.of(documentRoot, chapterOne));

        for (String question : List.of(
                "Tóm tắt nội dung môn học",
                "Tổng quan môn học",
                "Khái quát toàn bộ tài liệu",
                "Môn học này nói về gì?",
                "Cho tôi tóm tắt nội dung chính của môn học",
                "Tổng hợp ý chính giáo trình"
        )) {
            ScopeResolution resolved = resolverService.resolveDetailed(session, question);

            assertThat(resolved.status())
                    .as(question)
                    .isEqualTo(ScopeResolution.Status.RESOLVED);
            assertThat(resolved.node())
                    .as(question)
                    .isEqualTo(documentRoot);
        }
        verifyNoInteractions(llm);
    }

    @Test
    void resolveDetailed_ambiguousLexicalCanUseLlmBoundedFallback() {
        DocumentNodeRepository repository = mock(DocumentNodeRepository.class);
        LlmScopeDisambiguationService llm = mock(LlmScopeDisambiguationService.class);
        RagScopeResolverService resolverService = new RagScopeResolverService(repository, llm);
        ChatSession session = session();
        DocumentNode sectionA = node(201L, "section", "Nhà nước", "Chương 1 > Nhà nước", 2);
        DocumentNode sectionB = node(202L, "section", "Pháp luật", "Chương 1 > Pháp luật", 3);

        when(repository.findBySubjectIdAndNodeTypeInOrderByOrderIndexAsc(eq(7L), any()))
                .thenReturn(List.of(sectionA, sectionB));
        when(llm.resolve(eq("Tóm tắt nhà nước pháp luật"), any()))
                .thenReturn(Optional.of(ScopeResolution.resolved(sectionA, 0.8, "llm", List.of(sectionA, sectionB))));

        ScopeResolution resolved = resolverService.resolveDetailed(session, "Tóm tắt nhà nước pháp luật");

        assertThat(resolved.status()).isEqualTo(ScopeResolution.Status.RESOLVED);
        assertThat(resolved.node()).isEqualTo(sectionA);
    }

    @Test
    void resolveDeterministicOnly_doesNotUseLlmForAmbiguousResolution() {
        DocumentNodeRepository repository = mock(DocumentNodeRepository.class);
        LlmScopeDisambiguationService llm = mock(LlmScopeDisambiguationService.class);
        RagScopeResolverService resolverService = new RagScopeResolverService(repository, llm);
        ChatSession session = session();
        DocumentNode chapterA = node(101L, "chapter", "Chương I: Mở đầu", "Chương I", 1);
        DocumentNode chapterB = node(102L, "chapter", "Chương I: Nội dung khác", "Chương I", 2);

        when(repository.findBySubjectIdAndNodeTypeInOrderByOrderIndexAsc(eq(7L), any()))
                .thenReturn(List.of(chapterA, chapterB));

        ScopeResolution resolved = resolverService.resolveDeterministicOnly(session, "Tóm tắt chương I");

        assertThat(resolved.status()).isEqualTo(ScopeResolution.Status.AMBIGUOUS);
        verifyNoInteractions(llm);
    }

    private ChatSession session() {
        Subject subject = Subject.builder()
                .name("Triết học")
                .code("TRIET")
                .build();
        subject.setId(7L);
        ChatSession session = ChatSession.builder()
                .subject(subject)
                .sessionType(ChatSessionType.KNOWLEDGE_QA)
                .active(true)
                .build();
        session.setId(5L);
        return session;
    }

    private DocumentNode node(Long id, String nodeType, String title, String sectionPath, int orderIndex) {
        Document document = Document.builder()
                .title("Giáo trình")
                .build();
        document.setId(1L);
        DocumentNode node = DocumentNode.builder()
                .document(document)
                .nodeType(nodeType)
                .title(title)
                .sectionPath(sectionPath)
                .orderIndex(orderIndex)
                .build();
        node.setId(id);
        return node;
    }
}
