package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import com.example.teacherassistantai.common.enumerate.SubjectType;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import com.example.teacherassistantai.repository.SubjectRepository;
import com.example.teacherassistantai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.AuditorAware;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("dev")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        DocumentNodeScopeService.class,
        DocumentNodeScopeServiceTest.TestConfig.class
})
class DocumentNodeScopeServiceTest {

    @Autowired
    private DocumentNodeScopeService scopeService;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentNodeRepository documentNodeRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @Test
    void getScope_returnsDescendantChunksInSourceOrderAndStableHash() {
        Subject subject = subjectRepository.save(Subject.builder()
                .name("Scope Subject " + UUID.randomUUID())
                .code("SCP-" + UUID.randomUUID().toString().substring(0, 8))
                .subjectType(SubjectType.TEXT_BASED)
                .build());

        User uploader = userRepository.save(User.builder()
                .fullName("Scope Uploader")
                .email("scope-uploader-" + UUID.randomUUID() + "@example.com")
                .password("secret123")
                .enabled(true)
                .roles(new HashSet<>())
                .build());

        Document document = documentRepository.save(Document.builder()
                .title("Scope Document")
                .originalObjectKey("uploads/scope/" + UUID.randomUUID() + ".pdf")
                .fileType("PDF")
                .fileSizeBytes(2048L)
                .subject(subject)
                .uploadedBy(uploader)
                .status(DocumentStatus.READY)
                .build());

        DocumentNode chapter = documentNodeRepository.save(node(document, null, "n1", "chapter",
                "Chương 1", "Chương 1", 1));
        DocumentNode section = documentNodeRepository.save(node(document, chapter, "n2", "section",
                "I. Mục tiêu", "Chương 1 > I. Mục tiêu", 2));
        DocumentNode sibling = documentNodeRepository.save(node(document, null, "n3", "chapter",
                "Chương 2", "Chương 2", 3));

        DocumentChunk later = documentChunkRepository.save(chunk(document, section, 2, 2, "Nội dung sau"));
        DocumentChunk earlier = documentChunkRepository.save(chunk(document, chapter, 1, 1, "Nội dung trước"));
        DocumentChunk outside = documentChunkRepository.save(chunk(document, sibling, 3, 3, "Không thuộc scope"));

        DocumentNodeScopeService.NodeScope scope = scopeService.getScope(chapter.getId());

        assertThat(scope.rootNode().getId()).isEqualTo(chapter.getId());
        assertThat(scope.chunks())
                .extracting(DocumentChunk::getId)
                .containsExactly(earlier.getId(), later.getId())
                .doesNotContain(outside.getId());
        assertThat(scope.sourceHash()).hasSize(64);

        String originalHash = scope.sourceHash();
        later.setContent("Nội dung sau đã đổi");

        assertThat(scopeService.sourceHash(chapter, java.util.List.of(earlier, later)))
                .isNotEqualTo(originalHash);
    }

    private DocumentNode node(Document document,
                              DocumentNode parent,
                              String nodeKey,
                              String nodeType,
                              String title,
                              String sectionPath,
                              int orderIndex) {
        return DocumentNode.builder()
                .document(document)
                .parent(parent)
                .subjectId(document.getSubject().getId())
                .nodeKey(nodeKey)
                .nodeType(nodeType)
                .level(parent == null ? 1 : parent.getLevel() + 1)
                .title(title)
                .sectionPath(sectionPath)
                .orderIndex(orderIndex)
                .metadataJsonb(new java.util.HashMap<>())
                .build();
    }

    private DocumentChunk chunk(Document document,
                                DocumentNode node,
                                int chunkIndex,
                                int sourceOrder,
                                String content) {
        return DocumentChunk.builder()
                .document(document)
                .subjectId(document.getSubject().getId())
                .node(node)
                .parentNode(node.getParent())
                .chunkIndex(chunkIndex)
                .sourceOrder(sourceOrder)
                .chunkType("TEXT")
                .sectionPath(node.getSectionPath())
                .content(content)
                .tokenCount(10)
                .metadataJsonb(new java.util.HashMap<>())
                .build();
    }

    @TestConfiguration
    static class TestConfig {
        @Bean(name = "auditorProvider")
        AuditorAware<Long> auditorProvider() {
            return () -> Optional.of(1L);
        }

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager();
        }
    }
}
