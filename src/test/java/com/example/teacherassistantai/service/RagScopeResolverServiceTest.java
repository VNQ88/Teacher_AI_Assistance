package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.ChatSessionType;
import com.example.teacherassistantai.entity.ChatSession;
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
import static org.mockito.Mockito.when;

class RagScopeResolverServiceTest {

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
        DocumentNode node = DocumentNode.builder()
                .nodeType(nodeType)
                .title(title)
                .sectionPath(sectionPath)
                .orderIndex(orderIndex)
                .build();
        node.setId(id);
        return node;
    }
}
