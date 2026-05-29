package com.example.teacherassistantai.service.agent;

import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.service.RagScopeResolverService;
import com.example.teacherassistantai.service.ScopeResolution;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DocumentScopeAgent {

    private final RagScopeResolverService scopeResolverService;

    public Optional<DocumentNode> resolve(ChatSession session, String question) {
        return scopeResolverService.resolve(session, question);
    }

    public ScopeResolution resolveDetailed(ChatSession session, String question) {
        return scopeResolverService.resolveDetailed(session, question);
    }

    public AgentResult unresolved(ScopeResolution resolution) {
        if (resolution != null && resolution.status() == ScopeResolution.Status.AMBIGUOUS) {
            return AgentResult.message(
                    "Tôi chưa chắc phần/chương/mục bạn muốn dùng. " +
                    "Hãy hỏi rõ hơn bằng tên hoặc số phần/chương, ví dụ: \"Tóm tắt Chương 2\"."
            );
        }
        return notFound();
    }

    public AgentResult notFound() {
        return AgentResult.message(
                "Tôi chưa xác định được phần/chương/mục bạn muốn dùng. " +
                "Hãy hỏi rõ hơn, ví dụ: \"Tóm tắt Chương 2\" hoặc \"Tạo bộ câu hỏi ôn tập Chương 2\"."
        );
    }
}
