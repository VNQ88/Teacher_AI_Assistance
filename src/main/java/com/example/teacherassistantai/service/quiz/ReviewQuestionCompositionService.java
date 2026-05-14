package com.example.teacherassistantai.service.quiz;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.integration.ai.DigitalOceanAiRateLimiter;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.repository.DocumentNodeRepository;
import com.example.teacherassistantai.service.DocumentEnrichmentBacklogService;
import com.example.teacherassistantai.service.InternalCitationSanitizer;
import com.example.teacherassistantai.service.RagArtifactChatHandlerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ReviewQuestionCompositionService {

    private final DocumentNodeRepository nodeRepository;
    private final DocumentNodeArtifactRepository artifactRepository;
    private final HierarchicalQuizEnrichmentService enrichmentService;
    private final DocumentEnrichmentBacklogService backlogService;
    private final DigitalOceanAiRateLimiter rateLimiter;
    private final RagProperties ragProperties;
    private final RagArtifactChatHandlerService handlerService;
    private final InternalCitationSanitizer citationSanitizer;

    public ReviewQuestionCompositionResult composeForPart(DocumentNode partNode) {
        List<DocumentNode> chapters = chaptersForPart(partNode);
        return compose(
                partNode,
                ScopeKind.PART,
                List.of(new ChapterGroup(partNode, chapters))
        );
    }

    public ReviewQuestionCompositionResult composeForDocument(DocumentNode documentNode) {
        List<ChapterGroup> groups = chapterGroupsForDocument(documentNode);
        return compose(documentNode, ScopeKind.DOCUMENT, groups);
    }

    private ReviewQuestionCompositionResult compose(DocumentNode scopeNode,
                                                    ScopeKind scopeKind,
                                                    List<ChapterGroup> groups) {
        List<DocumentNode> chapters = groups.stream()
                .flatMap(group -> group.chapters().stream())
                .toList();
        if (chapters.isEmpty()) {
            return new ReviewQuestionCompositionResult(
                    "Không tìm thấy chương phù hợp để tạo bộ câu hỏi cho phạm vi này.",
                    List.of(), List.of(), List.of(), false, false
            );
        }

        Map<Long, DocumentNodeArtifact> artifactsByChapterId = latestCompletedArtifacts(chapters);
        List<DocumentNode> missingChapters = chapters.stream()
                .filter(chapter -> !artifactsByChapterId.containsKey(chapter.getId()))
                .toList();

        boolean summaryBacklog = hasSummaryBacklog(scopeNode);
        boolean ratePaused = rateLimiter.isBackgroundPaused();
        List<DocumentNode> queuedChapters = queueMissingChapters(missingChapters, summaryBacklog, ratePaused);

        Selection selection = selectQuestions(groups, artifactsByChapterId, scopeKind);
        if (selection.sections().isEmpty()) {
            return new ReviewQuestionCompositionResult(
                    emptySelectionMessage(summaryBacklog, ratePaused, missingChapters),
                    List.of(), missingChapters, queuedChapters, false, missingChapters.isEmpty()
            );
        }

        String answer = render(scopeNode, scopeKind, selection.sections(), missingChapters, summaryBacklog, ratePaused);
        List<DocumentChunk> sources = handlerService.sourceChunksFromCitations(
                Map.of("questions", selection.selectedQuestions())
        );
        return new ReviewQuestionCompositionResult(
                answer,
                sources,
                missingChapters,
                queuedChapters,
                true,
                missingChapters.isEmpty()
        );
    }

    private Map<Long, DocumentNodeArtifact> latestCompletedArtifacts(List<DocumentNode> chapters) {
        List<Long> chapterIds = chapters.stream()
                .map(DocumentNode::getId)
                .filter(id -> id != null)
                .toList();
        if (chapterIds.isEmpty()) {
            return Map.of();
        }
        List<DocumentNodeArtifact> artifacts = artifactRepository
                .findByNodeIdsAndArtifactTypeAndStatusOrderByNodeOrderAndUpdatedAt(
                        chapterIds,
                        DocumentNodeArtifactType.REVIEW_QUESTION_SET,
                        DocumentNodeArtifactStatus.COMPLETED
                );
        Map<Long, DocumentNodeArtifact> latest = new HashMap<>();
        for (DocumentNodeArtifact artifact : artifacts) {
            if (artifact.getDocumentNode() == null || artifact.getDocumentNode().getId() == null) {
                continue;
            }
            latest.putIfAbsent(artifact.getDocumentNode().getId(), artifact);
        }
        return latest;
    }

    private List<DocumentNode> queueMissingChapters(List<DocumentNode> missingChapters,
                                                    boolean summaryBacklog,
                                                    boolean ratePaused) {
        if (missingChapters.isEmpty() || summaryBacklog || ratePaused) {
            return List.of();
        }
        int limit = ragProperties.getEnrichment()
                .getReviewQuestionComposition()
                .getMaxMissingQueuePerRequest();
        List<DocumentNode> queued = missingChapters.stream().limit(limit).toList();
        for (DocumentNode chapter : queued) {
            enrichmentService.enqueueChapterQuizGeneration(chapter.getId());
        }
        return queued;
    }

    private Selection selectQuestions(List<ChapterGroup> groups,
                                      Map<Long, DocumentNodeArtifact> artifactsByChapterId,
                                      ScopeKind scopeKind) {
        int perChapterLimit = perChapterLimit(scopeKind);
        int remaining = totalLimit(scopeKind);
        List<ChapterQuestionSection> sections = new ArrayList<>();
        List<Map<String, Object>> selectedQuestions = new ArrayList<>();

        for (ChapterGroup group : groups) {
            for (DocumentNode chapter : group.chapters()) {
                if (remaining <= 0) {
                    return new Selection(sections, selectedQuestions);
                }
                DocumentNodeArtifact artifact = artifactsByChapterId.get(chapter.getId());
                if (artifact == null) {
                    continue;
                }
                List<Map<String, Object>> questions = questions(artifact.getContentJsonb());
                if (questions.isEmpty()) {
                    continue;
                }
                int count = Math.min(Math.min(perChapterLimit, questions.size()), remaining);
                List<Map<String, Object>> selected = questions.subList(0, count);
                sections.add(new ChapterQuestionSection(group.part(), chapter, selected));
                selectedQuestions.addAll(selected);
                remaining -= count;
            }
        }
        return new Selection(sections, selectedQuestions);
    }

    private String render(DocumentNode scopeNode,
                          ScopeKind scopeKind,
                          List<ChapterQuestionSection> sections,
                          List<DocumentNode> missingChapters,
                          boolean summaryBacklog,
                          boolean ratePaused) {
        StringBuilder questionSection = new StringBuilder();
        StringBuilder answerSection = new StringBuilder();
        questionSection.append(title(scopeNode, scopeKind)).append("\n");
        answerSection.append("\n\n---\n**Đáp án:**");

        Long currentPartId = null;
        Long answerCurrentPartId = null;
        int index = 1;
        for (ChapterQuestionSection section : sections) {
            currentPartId = appendPartHeading(questionSection, currentPartId, section.part(), scopeKind);
            questionSection.append("\n\n### ").append(displayName(section.chapter())).append('\n');

            answerCurrentPartId = appendPartHeading(answerSection, answerCurrentPartId, section.part(), scopeKind);
            answerSection.append("\n\n### ").append(displayName(section.chapter()));

            for (Map<String, Object> question : section.questions()) {
                int questionIndex = index++;
                questionSection.append("\n").append(questionIndex).append(". ")
                        .append(questionPrefix(question))
                        .append(sanitize(asString(question.get("question"))))
                        .append('\n');
                appendOptions(questionSection, question.get("options"));

                answerSection.append("\n").append(questionIndex).append(". Đáp án: ")
                        .append(formatAnswer(question.get("correctAnswer")));
                String explanation = asString(question.get("answerExplanation"));
                if (explanation != null && !explanation.isBlank()) {
                    answerSection.append("\n   Giải thích: ").append(sanitize(explanation));
                }
                answerSection.append('\n');
            }
        }

        appendMissingNote(answerSection, missingChapters, summaryBacklog, ratePaused);
        return (questionSection.toString() + answerSection).trim();
    }

    private Long appendPartHeading(StringBuilder builder, Long currentPartId, DocumentNode part, ScopeKind scopeKind) {
        if (scopeKind != ScopeKind.DOCUMENT || part == null) {
            return currentPartId;
        }
        Long partId = part.getId();
        if (partId != null && partId.equals(currentPartId)) {
            return currentPartId;
        }
        builder.append("\n\n## ").append(displayName(part));
        return partId;
    }

    @SuppressWarnings("unchecked")
    private void appendOptions(StringBuilder builder, Object rawOptions) {
        if (!(rawOptions instanceof List<?> options)) {
            return;
        }
        for (Object option : options) {
            if (!(option instanceof Map<?, ?> rawOption)) {
                continue;
            }
            Map<String, Object> optionMap = (Map<String, Object>) rawOption;
            String label = asString(optionMap.get("label"));
            String content = asString(optionMap.get("content"));
            builder.append("- ")
                    .append(label == null || label.isBlank() ? "" : sanitize(label) + ". ")
                    .append(sanitize(content))
                    .append('\n');
        }
    }

    private void appendMissingNote(StringBuilder builder,
                                   List<DocumentNode> missingChapters,
                                   boolean summaryBacklog,
                                   boolean ratePaused) {
        if (missingChapters.isEmpty()) {
            return;
        }
        builder.append("\n\nGhi chú:\n");
        if (summaryBacklog) {
            builder.append("Các chương sau sẽ được tạo câu hỏi sau khi summary sẵn sàng: ");
        } else if (ratePaused) {
            builder.append("Các chương sau sẽ tiếp tục được tạo sau khi hết giới hạn AI: ");
        } else {
            builder.append("Các chương sau đang được tạo thêm câu hỏi: ");
        }
        builder.append(joinNames(missingChapters)).append('.');
    }

    private List<ChapterGroup> chapterGroupsForDocument(DocumentNode documentNode) {
        Long documentId = documentNode.getDocument() == null ? null : documentNode.getDocument().getId();
        if (documentId == null) {
            return List.of();
        }
        List<DocumentNode> parts = nodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(documentId, "part");
        List<DocumentNode> allChapters = nodeRepository.findByDocumentIdAndNodeTypeOrderByOrderIndexAsc(documentId, "chapter");
        if (parts.isEmpty()) {
            return List.of(new ChapterGroup(null, allChapters));
        }

        Set<Long> groupedChapterIds = new LinkedHashSet<>();
        List<ChapterGroup> groups = new ArrayList<>();
        for (DocumentNode part : parts) {
            List<DocumentNode> chapters = chaptersForPart(part);
            if (chapters.isEmpty()) {
                continue;
            }
            chapters.stream().map(DocumentNode::getId).forEach(groupedChapterIds::add);
            groups.add(new ChapterGroup(part, chapters));
        }

        List<DocumentNode> directChapters = allChapters.stream()
                .filter(chapter -> chapter.getId() == null || !groupedChapterIds.contains(chapter.getId()))
                .toList();
        if (!directChapters.isEmpty()) {
            groups.add(new ChapterGroup(null, directChapters));
        }
        return groups;
    }

    private List<DocumentNode> chaptersForPart(DocumentNode partNode) {
        List<DocumentNode> directChapters = nodeRepository.findByParentIdAndNodeTypeOrderByOrderIndexAsc(
                partNode.getId(), "chapter");
        if (!directChapters.isEmpty()) {
            return directChapters;
        }

        List<DocumentNode> chapters = new ArrayList<>();
        ArrayDeque<DocumentNode> queue = new ArrayDeque<>(nodeRepository.findByParentIdOrderByOrderIndexAsc(partNode.getId()));
        while (!queue.isEmpty()) {
            DocumentNode node = queue.removeFirst();
            if ("chapter".equalsIgnoreCase(String.valueOf(node.getNodeType()))) {
                chapters.add(node);
                continue;
            }
            queue.addAll(nodeRepository.findByParentIdOrderByOrderIndexAsc(node.getId()));
        }
        return chapters;
    }

    private boolean hasSummaryBacklog(DocumentNode scopeNode) {
        Long documentId = scopeNode.getDocument() == null ? null : scopeNode.getDocument().getId();
        return documentId != null && backlogService.hasSummaryBacklog(documentId);
    }

    private String emptySelectionMessage(boolean summaryBacklog,
                                         boolean ratePaused,
                                         List<DocumentNode> missingChapters) {
        if (summaryBacklog) {
            return "Tài liệu vẫn đang hoàn tất tóm tắt. Bộ câu hỏi ôn tập sẽ được tạo sau khi summary sẵn sàng.";
        }
        if (ratePaused) {
            return "Bộ câu hỏi ôn tập đang tạm dừng do giới hạn AI và sẽ tiếp tục tự động sau.";
        }
        if (!missingChapters.isEmpty()) {
            return "Bộ câu hỏi cho phạm vi này đang được tạo theo từng chương. Vui lòng hỏi lại sau ít phút.";
        }
        return "Bộ câu hỏi cho phạm vi này chưa có dữ liệu hợp lệ.";
    }

    private String title(DocumentNode scopeNode, ScopeKind scopeKind) {
        if (scopeKind == ScopeKind.PART) {
            return "Bộ câu hỏi ôn tập " + displayName(scopeNode) + ":";
        }
        String documentTitle = scopeNode.getDocument() == null ? null : scopeNode.getDocument().getTitle();
        if (documentTitle == null || documentTitle.isBlank()) {
            return "Bộ câu hỏi ôn tập toàn bộ tài liệu:";
        }
        return "Bộ câu hỏi ôn tập toàn bộ tài liệu " + documentTitle + ":";
    }

    private String questionPrefix(Map<String, Object> question) {
        String type = asString(question.get("type"));
        String difficulty = asString(question.get("difficulty"));
        if ((type == null || type.isBlank()) && (difficulty == null || difficulty.isBlank())) {
            return "";
        }
        StringBuilder prefix = new StringBuilder("[");
        if (type != null && !type.isBlank()) {
            prefix.append(sanitize(type));
        }
        if (difficulty != null && !difficulty.isBlank()) {
            if (prefix.length() > 1) {
                prefix.append(" - ");
            }
            prefix.append(sanitize(difficulty));
        }
        return prefix.append("] ").toString();
    }

    private String formatAnswer(Object answer) {
        if (answer instanceof Boolean booleanAnswer) {
            return booleanAnswer ? "Đúng" : "Sai";
        }
        return sanitize(asString(answer));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> questions(Map<String, Object> content) {
        if (content == null || !(content.get("questions") instanceof List<?> rawQuestions)) {
            return List.of();
        }
        List<Map<String, Object>> questions = new ArrayList<>();
        for (Object item : rawQuestions) {
            if (item instanceof Map<?, ?> rawQuestion) {
                questions.add((Map<String, Object>) rawQuestion);
            }
        }
        return questions;
    }

    private int perChapterLimit(ScopeKind scopeKind) {
        RagProperties.Enrichment.ReviewQuestionComposition config =
                ragProperties.getEnrichment().getReviewQuestionComposition();
        return scopeKind == ScopeKind.PART
                ? config.getMaxQuestionsPerChapterInPart()
                : config.getMaxQuestionsPerChapterInDocument();
    }

    private int totalLimit(ScopeKind scopeKind) {
        RagProperties.Enrichment.ReviewQuestionComposition config =
                ragProperties.getEnrichment().getReviewQuestionComposition();
        return scopeKind == ScopeKind.PART
                ? config.getMaxTotalQuestionsInPart()
                : config.getMaxTotalQuestionsInDocument();
    }

    private String displayName(DocumentNode node) {
        if (node == null) {
            return "Nội dung không thuộc phần";
        }
        String path = asString(node.getSectionPath());
        if (path != null && !path.isBlank()) {
            return sanitize(path);
        }
        String title = asString(node.getTitle());
        return title == null || title.isBlank() ? "node " + node.getId() : sanitize(title);
    }

    private String joinNames(List<DocumentNode> nodes) {
        return nodes.stream()
                .map(this::displayName)
                .toList()
                .stream()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private String sanitize(String value) {
        return citationSanitizer.sanitize(value == null ? "" : value);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private enum ScopeKind {
        PART,
        DOCUMENT
    }

    private record ChapterGroup(DocumentNode part, List<DocumentNode> chapters) {
    }

    private record ChapterQuestionSection(DocumentNode part,
                                          DocumentNode chapter,
                                          List<Map<String, Object>> questions) {
    }

    private record Selection(List<ChapterQuestionSection> sections,
                             List<Map<String, Object>> selectedQuestions) {
    }

    public record ReviewQuestionCompositionResult(
            String answer,
            List<DocumentChunk> sources,
            List<DocumentNode> missingChapters,
            List<DocumentNode> queuedChapters,
            boolean hasAnyCompletedChapter,
            boolean fullyCovered
    ) {
    }
}
