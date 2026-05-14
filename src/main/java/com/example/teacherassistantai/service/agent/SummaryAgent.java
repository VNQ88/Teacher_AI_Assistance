package com.example.teacherassistantai.service.agent;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.entity.DocumentChunk;
import com.example.teacherassistantai.entity.DocumentNode;
import com.example.teacherassistantai.entity.DocumentNodeArtifact;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.integration.ai.AiChatGateway;
import com.example.teacherassistantai.integration.ai.AiWorkload;
import com.example.teacherassistantai.repository.DocumentNodeArtifactRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import com.example.teacherassistantai.service.DocumentOutlineSummaryRenderer;
import com.example.teacherassistantai.service.OriginalSummaryNodeService;
import com.example.teacherassistantai.service.RagArtifactChatHandlerService;
import com.example.teacherassistantai.service.VectorRetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryAgent {

    private static final int RAG_TOP_K = 20;

    private final DocumentNodeArtifactRepository artifactRepository;
    private final DocumentRepository documentRepository;
    private final RagArtifactChatHandlerService handlerService;
    private final VectorRetrievalService retrievalService;
    private final AiChatGateway aiChatGateway;
    private final RedisTemplate<String, String> redisTemplate;
    private final RagProperties ragProperties;
    private final PlatformTransactionManager transactionManager;
    private final OriginalSummaryNodeService originalSummaryNodeService;
    private final DocumentOutlineSummaryRenderer outlineSummaryRenderer;

    public AgentResult execute(RagChatState state) {
        DocumentNode node = state.getResolvedNode();
        Optional<String> outlineSummary = outlineSummaryRenderer.render(node);
        if (outlineSummary.isPresent()) {
            return AgentResult.hit(outlineSummary.get(), List.of());
        }
        if ("chapter".equals(node.getNodeType())) {
            Optional<OriginalSummaryNodeService.OriginalSummary> originalSummary =
                    originalSummaryNodeService.findForChapter(node);
            if (originalSummary.isPresent()) {
                return AgentResult.hit(renderOriginalSummary(node, originalSummary.get()), originalSummary.get().sources());
            }
        }

        Optional<DocumentNodeArtifact> artifact = fetchCompletedSummary(node.getId());

        if (artifact.isPresent()) {
            Map<String, Object> content = artifact.get().getContentJsonb();
            String answer = handlerService.renderSummary(content, node);
            List<DocumentChunk> sources = handlerService.sourceChunksFromCitations(content);
            return AgentResult.hit(answer, sources);
        }

        log.warn("SummaryAgent: no completed artifact for nodeId={}, using RAG fallback", node.getId());
        return ragFallbackSummary(state, node);
    }

    private String renderOriginalSummary(DocumentNode chapterNode, OriginalSummaryNodeService.OriginalSummary originalSummary) {
        return "Tóm tắt " + displayPath(chapterNode) + ":\n\n" + originalSummary.content();
    }

    private AgentResult ragFallbackSummary(RagChatState state, DocumentNode node) {
        String lockKey = "summary-fallback:" + node.getId();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofSeconds(60));

        if (!Boolean.TRUE.equals(acquired)) {
            Optional<DocumentNodeArtifact> done = fetchCompletedSummary(node.getId());
            if (done.isPresent()) {
                Map<String, Object> content = done.get().getContentJsonb();
                String answer = handlerService.renderSummary(content, node);
                List<DocumentChunk> sources = handlerService.sourceChunksFromCitations(content);
                return AgentResult.hit(answer, sources);
            }
            return AgentResult.message(
                    "Đang tạo tóm tắt cho phần này. Vui lòng hỏi lại sau vài giây.");
        }

        try {
            List<DocumentChunk> chunks = retrievalService.retrieve(
                    state.getSession(), state.getQuestion(), RAG_TOP_K);

            if (chunks.isEmpty()) {
                return AgentResult.message(
                        "Không tìm được nội dung phù hợp để tóm tắt cho " + displayPath(node) + ".");
            }

            String prompt = buildSummarizationPrompt(node, chunks);
            String answer = aiChatGateway.generateAnswer(prompt, 0.1, AiWorkload.RAG_CHAT);

            saveAsCompletedArtifact(node, answer, chunks);

            String prefix = "Tóm tắt dựa trên nội dung tìm được:\n\n";
            return AgentResult.fallback(prefix + answer, chunks);
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    private void saveAsCompletedArtifact(DocumentNode node, String answer, List<DocumentChunk> chunks) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> {
            try {
                Document document = loadDocumentForNode(node);
                String promptVersion = ragProperties.getEnrichment().getPromptVersion();
                String model = ragProperties.getAi().getChatModel();
                String sourceHash = computeRagFallbackSourceHash(node.getId(), chunks);

                Map<String, Object> contentJsonb = new LinkedHashMap<>();
                contentJsonb.put("nodeTitle", node.getTitle());
                contentJsonb.put("sectionPath", node.getSectionPath());
                contentJsonb.put("nodeType", node.getNodeType());
                contentJsonb.put("summary", answer);
                contentJsonb.put("generated", true);
                contentJsonb.put("generatedVia", "rag_fallback");
                contentJsonb.put("citations", buildCitations(chunks));

                DocumentNodeArtifact existing = artifactRepository
                        .findByDocumentNodeIdAndArtifactTypeAndPromptVersionAndModelAndSourceHash(
                                node.getId(),
                                DocumentNodeArtifactType.SUMMARY,
                                promptVersion,
                                model,
                                sourceHash)
                        .orElse(null);
                DocumentNodeArtifact artifact = existing == null
                        ? DocumentNodeArtifact.builder()
                                .document(document)
                                .documentNode(node)
                                .artifactType(DocumentNodeArtifactType.SUMMARY)
                                .promptVersion(promptVersion)
                                .model(model)
                                .sourceHash(sourceHash)
                                .build()
                        : existing;
                artifact.setStatus(DocumentNodeArtifactStatus.COMPLETED);
                artifact.setContentJsonb(contentJsonb);
                artifact.setErrorMessage(null);
                artifactRepository.save(artifact);
                log.info("SummaryAgent: saved RAG fallback artifact as COMPLETED for nodeId={}", node.getId());
            } catch (Exception ex) {
                log.warn("SummaryAgent: failed to persist RAG fallback artifact for nodeId={}",
                        node.getId(), ex);
                status.setRollbackOnly();
            }
        });
    }

    private Optional<DocumentNodeArtifact> fetchCompletedSummary(Long nodeId) {
        return artifactRepository.findLatestCompletedSummaryByNodeId(nodeId);
    }

    private Document loadDocumentForNode(DocumentNode node) {
        if (node == null || node.getDocument() == null || node.getDocument().getId() == null) {
            throw new ResourceNotFoundException("Document not found for summary node");
        }
        Long documentId = node.getDocument().getId();
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with id: " + documentId));
    }

    private String buildSummarizationPrompt(DocumentNode node, List<DocumentChunk> chunks) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Dua vao noi dung tai lieu sau, hay tom tat ")
                .append(displayPath(node))
                .append(" theo cau truc:\n");
        prompt.append("- Khai niem va dinh nghia chinh\n");
        prompt.append("- Cac diem quan trong\n");
        prompt.append("- Ket luan ngan gon\n\n");
        prompt.append("Yeu cau:\n");
        prompt.append("- Viet bang tieng Viet.\n");
        prompt.append("- Chi dua vao noi dung duoc cung cap, khong them thong tin ngoai.\n");
        prompt.append("- Khong xuat thong tin nhan dien chunk hay metadata.\n\n");
        prompt.append("Noi dung:\n");
        for (DocumentChunk chunk : chunks) {
            if (chunk == null || chunk.getContent() == null) {
                continue;
            }
            prompt.append(chunk.getContent()).append("\n\n");
        }
        return prompt.toString();
    }

    private String displayPath(DocumentNode node) {
        if (node == null) {
            return "phan duoc yeu cau";
        }
        String path = node.getSectionPath();
        if (path != null && !path.isBlank()) {
            return path;
        }
        String title = node.getTitle();
        return title == null || title.isBlank() ? "node " + node.getId() : title;
    }

    private List<Map<String, Object>> buildCitations(List<DocumentChunk> chunks) {
        List<Map<String, Object>> citations = new ArrayList<>();
        for (DocumentChunk chunk : chunks) {
            if (chunk == null || chunk.getId() == null) {
                continue;
            }
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("chunkId", chunk.getId());
            if (chunk.getPageFrom() != null) {
                citation.put("pageFrom", chunk.getPageFrom());
            }
            if (chunk.getPageTo() != null) {
                citation.put("pageTo", chunk.getPageTo());
            }
            citations.add(citation);
        }
        return citations;
    }

    private String computeRagFallbackSourceHash(Long nodeId, List<DocumentChunk> chunks) {
        StringBuilder seed = new StringBuilder("rag-fallback:");
        seed.append(nodeId).append(':');
        chunks.stream()
                .map(DocumentChunk::getId)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .forEach(id -> seed.append(id).append(','));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(seed.toString().getBytes(StandardCharsets.UTF_8));
            return "rag-" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is not available", ex);
        }
    }
}
