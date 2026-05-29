package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.integration.ai.AiChatCompletion;
import com.example.teacherassistantai.integration.ai.AiChatGateway;
import com.example.teacherassistantai.integration.ai.AiWorkload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmDocumentNodeArtifactGenerator implements DocumentNodeArtifactGenerator {

    private static final int PROMPT_WARN_CHARS = 200_000; // ~50K tokens

    private final AiChatGateway aiChatGateway;
    private final DocumentEnrichmentPromptBuilder promptBuilder;
    private final DocumentEnrichmentArtifactValidationService validationService;
    private final RagProperties ragProperties;

    @Override
    public boolean supports(DocumentNodeArtifactType artifactType) {
        return artifactType == DocumentNodeArtifactType.SUMMARY
                || artifactType == DocumentNodeArtifactType.REVIEW_QUESTION_SET;
    }

    @Override
    public DocumentNodeArtifactGenerationResult generate(DocumentNodeArtifactGenerationContext context) {
        String prompt = switch (context.artifactType()) {
            case SUMMARY -> promptBuilder.buildSummaryPrompt(context.document(), context.node(), context.chunks());
            case REVIEW_QUESTION_SET -> context.reviewQuestionContext() == null
                    ? promptBuilder.buildReviewQuestionPrompt(
                            context.document(),
                            context.node(),
                            context.chunks(),
                            context.minQuestionCount(),
                            context.maxQuestionCount()
                    )
                    : promptBuilder.buildReviewQuestionPrompt(context.document(), context.reviewQuestionContext());
        };
        warnIfLargePrompt(prompt, context.node().getId(), context.node().getNodeType(), context.artifactType().name());
        AiChatCompletion completion = aiChatGateway.generate(prompt, 0.2, workloadFor(context));
        String rawResponse = completion.content();
        Map<String, Object> content = parseOrRepair(context, prompt, rawResponse);
        return new DocumentNodeArtifactGenerationResult(
                content,
                tokenCount(prompt, rawResponse, completion)
        );
    }

    @Override
    public boolean supportsSummaryMode(SummaryMode summaryMode) {
        return summaryMode != null;
    }

    @Override
    public DocumentNodeArtifactGenerationResult generateSummary(SummaryGenerationContext context) {
        String prompt = switch (context.summaryMode()) {
            case SUBSECTION_LEVEL2_FROM_CHUNKS, SUBSECTION_FROM_CHUNKS, SECTION_FROM_CHUNKS_FALLBACK ->
                    promptBuilder.buildLeafSummaryPrompt(context.document(), context.node(), context.directChunks());
            case SUBSECTION_FROM_LEVEL2_AND_DIRECT_CHUNKS -> promptBuilder.buildSectionSummaryPrompt(context);
            case CHAPTER_FALLBACK -> context.childSummaries().isEmpty()
                    ? promptBuilder.buildLeafSummaryPrompt(context.document(), context.node(), context.directChunks())
                    : promptBuilder.buildParentSummaryPrompt(context);
            case CHAPTER_FROM_ORIGINAL_SUMMARY -> promptBuilder.buildOriginalSummaryPrompt(context);
            case PART_FALLBACK -> context.childSummaries().isEmpty()
                    ? promptBuilder.buildLeafSummaryPrompt(context.document(), context.node(), context.directChunks())
                    : promptBuilder.buildPartSummaryPrompt(context);
            case SECTION_FROM_SUBSECTIONS_AND_DIRECT_CHUNKS -> promptBuilder.buildSectionSummaryPrompt(context);
            case CHAPTER_FROM_SECTIONS -> promptBuilder.buildParentSummaryPrompt(context);
            case DOCUMENT_FROM_PARTS, DOCUMENT_FROM_CHAPTERS, DOCUMENT_FALLBACK ->
                    promptBuilder.buildDocumentSummaryPrompt(context);
            case PART_FROM_CHAPTERS -> promptBuilder.buildPartSummaryPrompt(context);
        };
        warnIfLargePrompt(prompt, context.node().getId(), context.node().getNodeType(), context.summaryMode().name());
        AiChatCompletion completion = aiChatGateway.generate(prompt, 0.2, AiWorkload.ENRICH_SUMMARY);
        String rawResponse = completion.content();
        Map<String, Object> content = parseSummaryOrRepair(context, prompt, rawResponse);
        return new DocumentNodeArtifactGenerationResult(
                content,
                tokenCount(prompt, rawResponse, completion)
        );
    }

    private Map<String, Object> parseOrRepair(DocumentNodeArtifactGenerationContext context,
                                              String originalPrompt,
                                              String rawResponse) {
        String response = rawResponse;
        int maxAttempts = Math.max(1, ragProperties.getEnrichment().getRepair().getMaxAttempts());
        InvalidDataException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return parse(context, response);
            } catch (InvalidDataException ex) {
                lastFailure = ex;
                if (attempt >= maxAttempts) {
                    throw ex;
                }
                String repairPrompt = buildRepairPrompt(context, originalPrompt, response, ex.getMessage());
                response = aiChatGateway.generate(repairPrompt, 0.0, workloadFor(context)).content();
            }
        }
        throw lastFailure == null ? new InvalidDataException("LLM response repair failed") : lastFailure;
    }

    private Map<String, Object> parse(DocumentNodeArtifactGenerationContext context, String rawResponse) {
        if (context.artifactType() == DocumentNodeArtifactType.REVIEW_QUESTION_SET
                && context.reviewQuestionContext() != null) {
            return validationService.parseAndValidateReviewQuestions(rawResponse, context.reviewQuestionContext());
        }
        return validationService.parseAndValidate(
                context.artifactType(),
                rawResponse,
                context.node(),
                context.chunks(),
                context.minQuestionCount(),
                context.maxQuestionCount()
        );
    }

    private Map<String, Object> parseSummaryOrRepair(SummaryGenerationContext context,
                                                     String originalPrompt,
                                                     String rawResponse) {
        try {
            return parseSummary(context, rawResponse);
        } catch (InvalidDataException ex) {
            String repairPrompt = buildSummaryRepairPrompt(context, originalPrompt, rawResponse, ex.getMessage());
            String repairedResponse = aiChatGateway.generate(repairPrompt, 0.0, AiWorkload.ENRICH_SUMMARY).content();
            return parseSummary(context, repairedResponse);
        }
    }

    private Map<String, Object> parseSummary(SummaryGenerationContext context, String rawResponse) {
        return validationService.parseAndValidateSummary(
                rawResponse,
                context.node(),
                context.directChunks(),
                context.childSummaries(),
                context.coverage(),
                context.summaryMode()
        );
    }

    private String buildRepairPrompt(DocumentNodeArtifactGenerationContext context,
                                     String originalPrompt,
                                     String rawResponse,
                                     String validationError) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Sua JSON enrichment bi loi schema. Chi tra ve mot JSON object hop le, khong markdown/code fence.\n");
        prompt.append("Validation error: ").append(validationError).append("\n");
        prompt.append("Artifact type: ").append(context.artifactType()).append("\n");
        prompt.append("Node title: ").append(context.node().getTitle()).append("\n");
        prompt.append("Section path: ").append(context.node().getSectionPath()).append("\n\n");
        if (context.artifactType() == DocumentNodeArtifactType.REVIEW_QUESTION_SET) {
            return buildReviewQuestionRepairPrompt(context, rawResponse, validationError);
        }
        prompt.append("Schema bat buoc:\n");
        prompt.append(String.format("""
                {
                  "summaryMode": "%s",
                  "summary": "string",
                  "keyPoints": ["string"],
                  "citations": %s
                }
                """, summaryModeFor(context), summaryCitationSchema(context.chunks())));
        prompt.append("Backend se tu them node metadata, coverage, childSummaries va childSummaryRefs; khong can output cac field do.\n");
        prompt.append("\nOriginal prompt/context:\n<<<PROMPT>>>\n")
                .append(originalPrompt)
                .append("\n<<<END_PROMPT>>>\n");
        prompt.append("\nInvalid JSON/response:\n<<<RESPONSE>>>\n")
                .append(rawResponse)
                .append("\n<<<END_RESPONSE>>>\n");
        return prompt.toString();
    }

    private String buildReviewQuestionRepairPrompt(DocumentNodeArtifactGenerationContext context,
                                                   String rawResponse,
                                                   String validationError) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Sua JSON bo cau hoi bi loi. Chi tra ve dung mot JSON object hop le, khong markdown/code fence.\n");
        prompt.append("Quy tac JSON: khong co raw newline trong string; neu can xuong dong trong string thi escape bang \\\\n; escape dau nhay kep.\n");
        prompt.append("Validation error: ").append(validationError).append("\n");
        prompt.append("Node title: ").append(context.node().getTitle()).append("\n");
        prompt.append("Section path: ").append(context.node().getSectionPath()).append("\n");
        prompt.append("Allowed citation chunkIds: ").append(allowedCitationIds(context)).append("\n\n");
        prompt.append("Schema bat buoc:\n");
        prompt.append("""
                {
                  "nodeTitle": "string",
                  "sectionPath": "string",
                  "nodeType": "string",
                  "inputMode": "RAW_CHUNKS|MIXED_CHILD_SUMMARIES_AND_REPRESENTATIVE_CHUNKS",
                  "questionCount": 15,
                  "questions": [
                    {
                      "sourceMode": "CHILD_SUMMARY|FALLBACK_CHUNK|REPRESENTATIVE_CHUNK|RAW_CHUNK",
                      "type": "MULTIPLE_CHOICE|TRUE_FALSE|FILL_BLANK",
                      "difficulty": "EASY|MEDIUM|HARD",
                      "question": "string",
                      "options": [{"label": "A", "content": "string"}],
                      "correctAnswer": "string or boolean",
                      "answerExplanation": "string",
                      "citations": [{"chunkId": 123, "pageFrom": 1, "pageTo": 2}]
                    }
                  ]
                }
                """);
        prompt.append("Rule hien thi: citations giu chunkId; question/options/correctAnswer/answerExplanation khong duoc nhac chunkId/chunk/sourceMode/ten block noi bo.\n");
        prompt.append("\nInvalid JSON/response (da cat gon neu qua dai):\n<<<RESPONSE>>>\n")
                .append(limitRawResponse(rawResponse))
                .append("\n<<<END_RESPONSE>>>\n");
        return prompt.toString();
    }

    private String buildSummaryRepairPrompt(SummaryGenerationContext context,
                                            String originalPrompt,
                                            String rawResponse,
                                            String validationError) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Sua JSON summary bottom-up bi loi schema. Chi tra ve mot JSON object hop le, khong markdown/code fence.\n");
        prompt.append("Validation error: ").append(validationError).append("\n");
        prompt.append("Summary mode: ").append(context.summaryMode()).append("\n");
        prompt.append("Node title: ").append(context.node().getTitle()).append("\n");
        prompt.append("Section path: ").append(context.node().getSectionPath()).append("\n\n");
        prompt.append("Backend se tu them node metadata, coverage, childSummaries va childSummaryRefs; khong can output cac field do.\n");
        prompt.append("Schema bat buoc:\n");
        prompt.append(String.format("""
                {
                  "summaryMode": "%s",
                  "summary": "string",
                  "keyPoints": ["string"],
                  "citations": %s
                }
                """,
                context.summaryMode().name(),
                summaryCitationSchema(context.directChunks())
        ));
        prompt.append("\nOriginal prompt/context:\n<<<PROMPT>>>\n")
                .append(originalPrompt)
                .append("\n<<<END_PROMPT>>>\n");
        prompt.append("\nInvalid JSON/response:\n<<<RESPONSE>>>\n")
                .append(rawResponse)
                .append("\n<<<END_RESPONSE>>>\n");
        return prompt.toString();
    }

    private void warnIfLargePrompt(String prompt, Object nodeId, String nodeType, String mode) {
        if (prompt != null && prompt.length() > PROMPT_WARN_CHARS) {
            log.warn("Large prompt: nodeId={} nodeType={} mode={} chars={} (~{}K tokens)",
                    nodeId, nodeType, mode, prompt.length(), prompt.length() / 4000);
        }
    }

    private Integer estimateTokenCount(String prompt, String response) {
        int chars = (prompt == null ? 0 : prompt.length()) + (response == null ? 0 : response.length());
        return Math.max(1, chars / 4);
    }

    private Integer tokenCount(String prompt, String response, AiChatCompletion completion) {
        if (completion != null && completion.usage() != null && completion.usage().totalTokens() != null) {
            return completion.usage().totalTokens();
        }
        return estimateTokenCount(prompt, response);
    }

    private String summaryCitationSchema(List<com.example.teacherassistantai.entity.DocumentChunk> chunks) {
        List<com.example.teacherassistantai.entity.DocumentChunk> safeChunks = chunks == null ? List.of() : chunks;
        if (safeChunks.isEmpty()) {
            return "[]";
        }
        Long chunkId = safeChunks.getFirst().getId();
        return "[{\"chunkId\": " + (chunkId == null ? 0 : chunkId) + ", \"pageFrom\": 1, \"pageTo\": 1}]";
    }

    private String summaryModeFor(DocumentNodeArtifactGenerationContext context) {
        return switch (context.node() == null ? "" : context.node().getNodeType()) {
            case "subsection_level2" -> SummaryMode.SUBSECTION_LEVEL2_FROM_CHUNKS.name();
            case "subsection" -> SummaryMode.SUBSECTION_FROM_CHUNKS.name();
            case "section" -> SummaryMode.SECTION_FROM_CHUNKS_FALLBACK.name();
            case "part" -> SummaryMode.PART_FALLBACK.name();
            default -> SummaryMode.CHAPTER_FALLBACK.name();
        };
    }

    private List<Long> allowedCitationIds(DocumentNodeArtifactGenerationContext context) {
        List<com.example.teacherassistantai.entity.DocumentChunk> chunks = context.reviewQuestionContext() == null
                ? context.chunks()
                : context.reviewQuestionContext().allowedCitationChunks();
        return (chunks == null ? List.<com.example.teacherassistantai.entity.DocumentChunk>of() : chunks).stream()
                .map(com.example.teacherassistantai.entity.DocumentChunk::getId)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
    }

    private String limitRawResponse(String rawResponse) {
        if (rawResponse == null) {
            return "";
        }
        int maxChars = Math.max(1, ragProperties.getEnrichment().getRepair().getMaxRawResponseChars());
        if (rawResponse.length() <= maxChars) {
            return rawResponse;
        }
        int half = maxChars / 2;
        return rawResponse.substring(0, half)
                + "\n...[truncated]...\n"
                + rawResponse.substring(rawResponse.length() - (maxChars - half));
    }

    private AiWorkload workloadFor(DocumentNodeArtifactGenerationContext context) {
        if (context.workloadOverride() != null) return context.workloadOverride();
        return context.artifactType() == DocumentNodeArtifactType.SUMMARY
                ? AiWorkload.ENRICH_SUMMARY
                : AiWorkload.ENRICH_REVIEW_QUESTION;
    }
}
