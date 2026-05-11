package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RagArtifactChatHandlerService {

    private final RagScopeResolverService scopeResolverService;
    private final DocumentNodeArtifactRepository artifactRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final DocumentEnrichmentService documentEnrichmentService;

    public ArtifactChatResult handle(ChatSession session, String question, RagChatIntent intent) {
        Optional<DocumentNode> resolvedNode = scopeResolverService.resolve(session, question);
        if (resolvedNode.isEmpty()) {
            return ArtifactChatResult.fallback("""
                    Tôi chưa xác định được phần/chương/mục bạn muốn dùng. Hãy hỏi rõ hơn, ví dụ: "Tóm tắt Chương 2" hoặc "Tạo bộ câu hỏi ôn tập Chương 2".
                    """.trim());
        }

        DocumentNode node = resolvedNode.get();
        DocumentNodeArtifactType artifactType = intent == RagChatIntent.SECTION_SUMMARY
                ? DocumentNodeArtifactType.SUMMARY
                : DocumentNodeArtifactType.REVIEW_QUESTION_SET;

        List<DocumentNodeArtifact> artifacts = artifactRepository.findLatestByNodeTypeAndStatus(
                node.getId(),
                artifactType,
                DocumentNodeArtifactStatus.COMPLETED
        );
        if (artifacts.isEmpty()) {
            if (artifactType == DocumentNodeArtifactType.REVIEW_QUESTION_SET) {
                return queueReviewQuestionsIfNeeded(node);
            }
            return ArtifactChatResult.fallback("""
                    Học liệu cho %s hiện chưa sẵn sàng. Tài liệu vẫn có thể hỏi đáp RAG, nhưng summary/câu hỏi ôn tập cần enrichment hoàn tất trước.
                    """.formatted(displayPath(node)));
        }

        DocumentNodeArtifact artifact = artifacts.getFirst();
        Map<String, Object> content = artifact.getContentJsonb();
        List<DocumentChunk> sources = sourceChunksFromCitations(content);
        String answer = artifactType == DocumentNodeArtifactType.SUMMARY
                ? renderSummary(content, node)
                : renderQuestions(content, node);
        return new ArtifactChatResult(answer, sources, 1.0, "HIGH", true);
    }

    private ArtifactChatResult queueReviewQuestionsIfNeeded(DocumentNode node) {
        DocumentEnrichmentService.OnDemandArtifactStatus status =
                documentEnrichmentService.prepareNodeArtifactGeneration(
                        node.getId(),
                        DocumentNodeArtifactType.REVIEW_QUESTION_SET
                );
        if (status == DocumentEnrichmentService.OnDemandArtifactStatus.QUEUED) {
            documentEnrichmentService.enqueueNodeEnrichment(
                    node.getId(),
                    false,
                    List.of(DocumentNodeArtifactType.REVIEW_QUESTION_SET)
            );
        }

        String answer = switch (status) {
            case QUEUED -> """
                    Bộ câu hỏi ôn tập cho %s đang được tạo. Vui lòng hỏi lại sau ít phút.
                    """.formatted(displayPath(node)).trim();
            case IN_PROGRESS -> """
                    Bộ câu hỏi ôn tập cho %s đang được tạo. Vui lòng hỏi lại sau ít phút.
                    """.formatted(displayPath(node)).trim();
            case COMPLETED -> """
                    Bộ câu hỏi ôn tập cho %s vừa hoàn tất. Vui lòng hỏi lại để xem kết quả.
                    """.formatted(displayPath(node)).trim();
        };
        return ArtifactChatResult.fallback(answer);
    }

    public String renderSummary(Map<String, Object> content, DocumentNode node) {
        String summary = asString(content.get("summary"));
        StringBuilder answer = new StringBuilder();
        answer.append("Tóm tắt ").append(displayPath(node)).append(":\n\n");
        answer.append(summary == null || summary.isBlank() ? "Chưa có nội dung tóm tắt hợp lệ." : summary);
        appendKeyPoints(answer, content);
        appendChildSummaryParagraphs(answer, content);
        return answer.toString();
    }

    @SuppressWarnings("unchecked")
    private void appendKeyPoints(StringBuilder answer, Map<String, Object> content) {
        Object rawKeyPoints = content.get("keyPoints");
        if (!(rawKeyPoints instanceof List<?> keyPoints) || keyPoints.isEmpty()) {
            return;
        }
        answer.append("\n\nCác ý chính:");
        for (Object keyPoint : keyPoints) {
            String value = asString(keyPoint);
            if (value != null && !value.isBlank()) {
                answer.append("\n- ").append(value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void appendChildSummaryParagraphs(StringBuilder answer, Map<String, Object> content) {
        String summaryMode = asString(content.get("summaryMode"));
        if (!"PART_FROM_CHAPTERS".equals(summaryMode) && !"PART_FALLBACK".equals(summaryMode)) {
            return;
        }
        Object rawChildSummaries = content.get("childSummaries");
        if (!(rawChildSummaries instanceof List<?> childSummaries) || childSummaries.isEmpty()) {
            return;
        }
        answer.append("\n\nCác nội dung chính:");
        for (Object item : childSummaries) {
            if (!(item instanceof Map<?, ?> rawChildSummary)) {
                continue;
            }
            Map<String, Object> childSummary = (Map<String, Object>) rawChildSummary;
            String title = asString(childSummary.get("title"));
            String childContent = asString(childSummary.get("summary"));
            if (childContent == null || childContent.isBlank()) {
                continue;
            }
            answer.append("\n\n");
            if (title != null && !title.isBlank()) {
                answer.append(title).append(": ");
            }
            answer.append(childContent);
        }
    }

    @SuppressWarnings("unchecked")
    public String renderQuestions(Map<String, Object> content, DocumentNode node) {
        Object rawQuestions = content.get("questions");
        if (!(rawQuestions instanceof List<?> questions) || questions.isEmpty()) {
            return "Bộ câu hỏi cho %s chưa có dữ liệu hợp lệ.".formatted(displayPath(node));
        }

        StringBuilder questionSection = new StringBuilder();
        StringBuilder answerSection = new StringBuilder();
        questionSection.append("Bộ câu hỏi ôn tập ").append(displayPath(node)).append(":\n");
        answerSection.append("\n\n---\n**Đáp án:**\n");

        int index = 1;
        for (Object item : questions) {
            if (!(item instanceof Map<?, ?> rawQuestion)) {
                continue;
            }
            Map<String, Object> question = (Map<String, Object>) rawQuestion;
            int questionIndex = index++;

            questionSection.append("\n").append(questionIndex).append(". ");
            questionSection.append("[")
                    .append(asString(question.get("type")))
                    .append(difficultySuffix(question))
                    .append("] ");
            questionSection.append(asString(question.get("question"))).append('\n');
            appendOptions(questionSection, question.get("options"));

            answerSection.append("\n").append(questionIndex).append(". ");
            answerSection.append("Đáp án: ").append(formatAnswer(question.get("correctAnswer"))).append('\n');
            String explanation = asString(question.get("answerExplanation"));
            if (explanation != null && !explanation.isBlank()) {
                answerSection.append("   Giải thích: ").append(explanation).append('\n');
            }
        }

        return (questionSection.toString() + answerSection.toString()).trim();
    }

    @SuppressWarnings("unchecked")
    private void appendOptions(StringBuilder answer, Object rawOptions) {
        if (!(rawOptions instanceof List<?> options)) {
            return;
        }
        for (Object option : options) {
            if (option instanceof Map<?, ?> rawOption) {
                Map<String, Object> optionMap = (Map<String, Object>) rawOption;
                answer.append("- ")
                        .append(asString(optionMap.get("label")))
                        .append(". ")
                        .append(asString(optionMap.get("content")))
                        .append('\n');
            }
        }
    }

    private String difficultySuffix(Map<String, Object> question) {
        String difficulty = asString(question.get("difficulty"));
        return difficulty == null || difficulty.isBlank() ? "" : " - " + difficulty;
    }

    private String formatAnswer(Object answer) {
        if (answer instanceof Boolean booleanAnswer) {
            return booleanAnswer ? "Đúng" : "Sai";
        }
        return asString(answer);
    }

    @SuppressWarnings("unchecked")
    public List<DocumentChunk> sourceChunksFromCitations(Map<String, Object> content) {
        Set<Long> chunkIds = new LinkedHashSet<>();
        collectCitationChunkIds(content.get("citations"), chunkIds);
        collectChildSummaryCitationChunkIds(content.get("childSummaries"), chunkIds);
        Object rawQuestions = content.get("questions");
        if (rawQuestions instanceof List<?> questions) {
            for (Object question : questions) {
                if (question instanceof Map<?, ?> questionMap) {
                    collectCitationChunkIds(((Map<String, Object>) questionMap).get("citations"), chunkIds);
                }
            }
        }
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        List<DocumentChunk> chunks = documentChunkRepository.findAllById(chunkIds);
        Map<Long, DocumentChunk> byId = chunks.stream()
                .filter(chunk -> chunk.getId() != null)
                .collect(java.util.stream.Collectors.toMap(DocumentChunk::getId, chunk -> chunk));
        List<DocumentChunk> ordered = new ArrayList<>();
        for (Long chunkId : chunkIds) {
            DocumentChunk chunk = byId.get(chunkId);
            if (chunk != null) {
                ordered.add(chunk);
            }
        }
        return ordered;
    }

    @SuppressWarnings("unchecked")
    private void collectChildSummaryCitationChunkIds(Object rawChildSummaries, Set<Long> chunkIds) {
        if (!(rawChildSummaries instanceof List<?> childSummaries)) {
            return;
        }
        for (Object childSummary : childSummaries) {
            if (childSummary instanceof Map<?, ?> rawChildSummary) {
                collectCitationChunkIds(((Map<String, Object>) rawChildSummary).get("citations"), chunkIds);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void collectCitationChunkIds(Object rawCitations, Set<Long> chunkIds) {
        if (!(rawCitations instanceof List<?> citations)) {
            return;
        }
        for (Object citation : citations) {
            if (citation instanceof Map<?, ?> rawCitation) {
                Object chunkId = ((Map<String, Object>) rawCitation).get("chunkId");
                if (chunkId instanceof Number number) {
                    chunkIds.add(number.longValue());
                }
            }
        }
    }

    private String displayPath(DocumentNode node) {
        String path = asString(node.getSectionPath());
        if (path != null && !path.isBlank()) {
            return path;
        }
        String title = asString(node.getTitle());
        return title == null || title.isBlank() ? "node " + node.getId() : title;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record ArtifactChatResult(
            String answer,
            List<DocumentChunk> sources,
            Double confidenceScore,
            String confidenceLevel,
            boolean artifactHit
    ) {
        static ArtifactChatResult fallback(String answer) {
            return new ArtifactChatResult(answer, List.of(), 0.35, "LOW", false);
        }
    }
}
