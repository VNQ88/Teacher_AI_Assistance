package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.integration.ai.AiChatGateway;
import com.example.teacherassistantai.integration.ai.AiWorkload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmScopeDisambiguationServiceTest {

    @Test
    void resolve_acceptsHighConfidenceValidNodeId() {
        AiChatGateway gateway = mock(AiChatGateway.class);
        LlmScopeDisambiguationService service = new LlmScopeDisambiguationService(gateway, new ObjectMapper());
        DocumentNode node = node(10L, "chapter", "Chương I");
        when(gateway.generateAnswer(anyString(), eq(0.0), eq(AiWorkload.RAG_CHAT)))
                .thenReturn("{\"nodeId\":10,\"confidence\":0.82,\"reason\":\"Title exact\"}");

        Optional<ScopeResolution> result = service.resolve("Tóm tắt chương I", List.of(node));

        assertThat(result).isPresent();
        assertThat(result.get().node()).isEqualTo(node);
    }

    @Test
    void resolve_rejectsLowConfidence() {
        AiChatGateway gateway = mock(AiChatGateway.class);
        LlmScopeDisambiguationService service = new LlmScopeDisambiguationService(gateway, new ObjectMapper());
        DocumentNode node = node(10L, "chapter", "Chương I");
        when(gateway.generateAnswer(anyString(), eq(0.0), eq(AiWorkload.RAG_CHAT)))
                .thenReturn("{\"nodeId\":10,\"confidence\":0.5,\"reason\":\"Weak\"}");

        assertThat(service.resolve("Tóm tắt chương I", List.of(node))).isEmpty();
    }

    @Test
    void resolve_rejectsNodeOutsideCandidates() {
        AiChatGateway gateway = mock(AiChatGateway.class);
        LlmScopeDisambiguationService service = new LlmScopeDisambiguationService(gateway, new ObjectMapper());
        DocumentNode node = node(10L, "chapter", "Chương I");
        when(gateway.generateAnswer(anyString(), eq(0.0), eq(AiWorkload.RAG_CHAT)))
                .thenReturn("{\"nodeId\":999,\"confidence\":0.9,\"reason\":\"Wrong\"}");

        assertThat(service.resolve("Tóm tắt chương I", List.of(node))).isEmpty();
    }

    @Test
    void resolve_rejectsInvalidJson() {
        AiChatGateway gateway = mock(AiChatGateway.class);
        LlmScopeDisambiguationService service = new LlmScopeDisambiguationService(gateway, new ObjectMapper());
        DocumentNode node = node(10L, "chapter", "Chương I");
        when(gateway.generateAnswer(anyString(), eq(0.0), eq(AiWorkload.RAG_CHAT)))
                .thenReturn("not-json");

        assertThat(service.resolve("Tóm tắt chương I", List.of(node))).isEmpty();
    }

    private DocumentNode node(Long id, String nodeType, String title) {
        DocumentNode node = DocumentNode.builder()
                .nodeType(nodeType)
                .title(title)
                .sectionPath(title)
                .orderIndex(1)
                .build();
        node.setId(id);
        return node;
    }
}
