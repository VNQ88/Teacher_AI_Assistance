package com.example.teacherassistantai.service.agent;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.service.DocumentNodeScopeService;
import com.example.teacherassistantai.service.RagArtifactChatHandlerService;
import com.example.teacherassistantai.service.quiz.HierarchicalQuizEnrichmentService;
import com.example.teacherassistantai.service.quiz.HierarchicalQuizEnrichmentService.QuizArtifactOutcome;
import com.example.teacherassistantai.service.quiz.QuizGenerationStrategy;
import com.example.teacherassistantai.service.quiz.ReviewQuestionCompositionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuizAgent {

    private final DocumentNodeArtifactRepository artifactRepository;
    private final RagArtifactChatHandlerService handlerService;
    private final DocumentNodeScopeService nodeScopeService;
    private final QuizGenerationStrategy strategy;
    private final HierarchicalQuizEnrichmentService enrichmentService;
    private final RedisTemplate<String, String> redisTemplate;
    private final ReviewQuestionCompositionService compositionService;

    public AgentResult execute(RagChatState state) {
        DocumentNode node = state.getResolvedNode();
        String nodeType = node.getNodeType() == null ? "" : node.getNodeType().toLowerCase(Locale.ROOT);

        return switch (nodeType) {
            case "part" -> fromComposition(compositionService.composeForPart(node));
            case "document", "subject" -> fromComposition(compositionService.composeForDocument(node));
            case "chapter" -> executeNodeLevel(node);
            case "summary", "review_questions" -> AgentResult.message(
                    "Không thể tạo bộ câu hỏi trực tiếp cho node hỗ trợ này. Hãy chọn phần, chương hoặc mục nội dung.");
            default -> executeNodeLevel(node);
        };
    }

    private AgentResult executeNodeLevel(DocumentNode node) {
        Optional<DocumentNodeArtifact> artifact = fetchCompletedQuiz(node.getId());
        if (artifact.isPresent()) {
            return hitFromArtifact(artifact.get(), node);
        }

        String lockKey = "artifact-lock:" + node.getId() + ":REVIEW_QUESTION_SET";
        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey))) {
            return AgentResult.message(
                    "Bộ câu hỏi ôn tập đang được tạo, vui lòng hỏi lại sau khoảng 1 phút.");
        }

        return generateOnDemand(node, lockKey);
    }

    private AgentResult fromComposition(ReviewQuestionCompositionService.ReviewQuestionCompositionResult result) {
        if (result.hasAnyCompletedChapter()) {
            return AgentResult.hit(result.answer(), result.sources());
        }
        return AgentResult.message(result.answer());
    }

    private AgentResult generateOnDemand(DocumentNode node, String lockKey) {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "od", Duration.ofMinutes(5));
        if (!Boolean.TRUE.equals(acquired)) {
            return AgentResult.message(
                    "Bộ câu hỏi ôn tập đang được tạo, vui lòng hỏi lại sau khoảng 1 phút.");
        }
        try {
            DocumentNodeScopeService.NodeScope scope = nodeScopeService.getScope(node.getId());
            QuizGenerationStrategy.QuizInputType inputType =
                    strategy.determine(node, scope.chunks().size());

            QuizArtifactOutcome outcome = enrichmentService.generateAndSaveQuizArtifactOnDemand(node, inputType);

            if (outcome == QuizArtifactOutcome.COMPLETED) {
                Optional<DocumentNodeArtifact> generated = fetchCompletedQuiz(node.getId());
                if (generated.isPresent()) {
                    return hitFromArtifact(generated.get(), node);
                }
            }
            return switch (outcome) {
                case RATE_LIMITED -> AgentResult.message(
                        "Hệ thống AI đang bận, vui lòng thử lại sau vài phút.");
                case SKIPPED -> AgentResult.message(
                        "Không tìm thấy nội dung để tạo câu hỏi cho phần này.");
                default -> AgentResult.message(
                        "Tạo bộ câu hỏi thất bại, vui lòng thử lại sau.");
            };
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    private Optional<DocumentNodeArtifact> fetchCompletedQuiz(Long nodeId) {
        return artifactRepository.findLatestByNodeTypeAndStatus(
                nodeId, DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                DocumentNodeArtifactStatus.COMPLETED
        ).stream().findFirst();
    }

    private AgentResult hitFromArtifact(DocumentNodeArtifact artifact, DocumentNode node) {
        String answer = handlerService.renderQuestions(artifact.getContentJsonb(), node);
        List<DocumentChunk> sources = handlerService.sourceChunksFromCitations(artifact.getContentJsonb());
        return AgentResult.hit(answer, sources);
    }
}
