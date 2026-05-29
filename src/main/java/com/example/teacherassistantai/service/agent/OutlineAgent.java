package com.example.teacherassistantai.service.agent;

import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.service.DocumentOutlineSummaryRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OutlineAgent {

    private static final String UNSUPPORTED_MESSAGE =
            "Hiện tôi chỉ hỗ trợ xem cấu trúc ở cấp tài liệu hoặc phần.";

    private final DocumentOutlineSummaryRenderer outlineSummaryRenderer;

    public AgentResult execute(RagChatState state) {
        DocumentNode node = state == null ? null : state.getResolvedNode();
        if (!supports(node)) {
            return AgentResult.message(UNSUPPORTED_MESSAGE);
        }
        return outlineSummaryRenderer.render(node)
                .map(answer -> new AgentResult(answer, List.of(), 1.0, "HIGH", false, false))
                .orElseGet(() -> AgentResult.message(UNSUPPORTED_MESSAGE));
    }

    private boolean supports(DocumentNode node) {
        if (node == null) {
            return false;
        }
        return "document".equals(node.getNodeType()) || "part".equals(node.getNodeType());
    }
}
