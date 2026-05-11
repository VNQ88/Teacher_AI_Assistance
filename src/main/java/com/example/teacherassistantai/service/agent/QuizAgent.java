package com.example.teacherassistantai.service.agent;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.service.DocumentNodeScopeService;
import com.example.teacherassistantai.service.RagArtifactChatHandlerService;
import com.example.teacherassistantai.service.quiz.HierarchicalQuizEnrichmentService;
import com.example.teacherassistantai.service.quiz.QuizGenerationStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
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

    public AgentResult execute(RagChatState state) {
        DocumentNode node = state.getResolvedNode();

        Optional<DocumentNodeArtifact> artifact = fetchCompletedQuiz(node.getId());
        if (artifact.isPresent()) {
            String answer = handlerService.renderQuestions(artifact.get().getContentJsonb(), node);
            return AgentResult.hit(answer, List.of());
        }

        String lockKey = "artifact-lock:" + node.getId() + ":REVIEW_QUESTION_SET";
        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey))) {
            return AgentResult.message(
                    "Bộ câu hỏi ôn tập đang được tạo, vui lòng hỏi lại sau khoảng 1 phút.");
        }

        return generateOnDemand(node, lockKey);
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

            enrichmentService.generateAndSaveQuizArtifact(node, inputType);

            Optional<DocumentNodeArtifact> generated = fetchCompletedQuiz(node.getId());
            if (generated.isPresent()) {
                String answer = handlerService.renderQuestions(generated.get().getContentJsonb(), node);
                return AgentResult.hit(answer, List.of());
            }
            return AgentResult.message("Không thể tạo bộ câu hỏi lúc này. Vui lòng thử lại sau.");
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
}
