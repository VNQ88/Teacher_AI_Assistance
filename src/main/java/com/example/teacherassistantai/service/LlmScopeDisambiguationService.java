package com.example.teacherassistantai.service;

import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.integration.ai.AiChatGateway;
import com.example.teacherassistantai.integration.ai.AiWorkload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LlmScopeDisambiguationService {

    private static final double ACCEPT_CONFIDENCE = 0.75;
    private static final int MAX_CANDIDATES = 20;

    private final AiChatGateway aiChatGateway;
    private final ObjectMapper objectMapper;

    @Autowired
    public LlmScopeDisambiguationService(AiChatGateway aiChatGateway) {
        this(aiChatGateway, new ObjectMapper());
    }

    LlmScopeDisambiguationService(AiChatGateway aiChatGateway, ObjectMapper objectMapper) {
        this.aiChatGateway = aiChatGateway;
        this.objectMapper = objectMapper;
    }

    public Optional<ScopeResolution> resolve(String question, List<DocumentNode> candidates) {
        if (question == null || question.isBlank() || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        List<DocumentNode> boundedCandidates = candidates.stream()
                .filter(node -> node != null && node.getId() != null)
                .limit(MAX_CANDIDATES)
                .toList();
        if (boundedCandidates.isEmpty()) {
            return Optional.empty();
        }

        try {
            String response = aiChatGateway.generateAnswer(buildPrompt(question, boundedCandidates), 0.0, AiWorkload.RAG_CHAT);
            JsonNode root = objectMapper.readTree(stripCodeFence(response));
            JsonNode nodeIdNode = root.path("nodeId");
            double confidence = root.path("confidence").asDouble(0.0);
            if (!nodeIdNode.isIntegralNumber() || confidence < ACCEPT_CONFIDENCE) {
                return Optional.empty();
            }

            long selectedNodeId = nodeIdNode.asLong();
            Set<Long> validIds = boundedCandidates.stream().map(DocumentNode::getId).collect(Collectors.toSet());
            if (!validIds.contains(selectedNodeId)) {
                return Optional.empty();
            }

            return boundedCandidates.stream()
                    .filter(node -> selectedNodeId == node.getId())
                    .findFirst()
                    .map(node -> ScopeResolution.resolved(
                            node,
                            confidence,
                            "llm_scope_disambiguation: " + root.path("reason").asText("selected by bounded candidates"),
                            boundedCandidates
                    ));
        } catch (Exception ex) {
            log.debug("LLM scope disambiguation failed", ex);
            return Optional.empty();
        }
    }

    private String buildPrompt(String question, List<DocumentNode> candidates) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Bạn cần xác định phần tài liệu mà người dùng đang hỏi.\n");
        prompt.append("Chỉ được chọn một nodeId trong danh sách candidates. Không được tạo node mới.\n");
        prompt.append("Nếu không chắc, trả {\"nodeId\": null, \"confidence\": 0, \"reason\": \"not sure\"}.\n");
        prompt.append("Chỉ trả JSON object, không markdown/code fence.\n\n");
        prompt.append("Câu hỏi người dùng: ").append(question).append("\n\n");
        prompt.append("Candidates:\n");
        for (DocumentNode node : candidates) {
            prompt.append("- nodeId=").append(node.getId())
                    .append("; nodeType=").append(value(node.getNodeType()))
                    .append("; title=").append(value(node.getTitle()))
                    .append("; sectionPath=").append(value(node.getSectionPath()))
                    .append("; pageFrom=").append(node.getPageFrom())
                    .append("; pageTo=").append(node.getPageTo())
                    .append("; orderIndex=").append(node.getOrderIndex())
                    .append('\n');
        }
        prompt.append("\nOutput schema: {\"nodeId\": 123, \"confidence\": 0.82, \"reason\": \"Title exact match\"}");
        return prompt.toString();
    }

    private String stripCodeFence(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        return trimmed.trim();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
