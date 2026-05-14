package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.integration.ai.AiChatCompletion;
import com.example.teacherassistantai.integration.ai.AiChatGateway;
import com.example.teacherassistantai.integration.ai.AiWorkload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmDocumentNodeArtifactGenerator implements DocumentNodeArtifactGenerator {

    private static final int PROMPT_WARN_CHARS = 200_000; // ~50K tokens

    private final AiChatGateway aiChatGateway;
    private final DocumentEnrichmentPromptBuilder promptBuilder;
    private final DocumentEnrichmentArtifactValidationService validationService;

    @Override
    public boolean supports(DocumentNodeArtifactType artifactType) {
        return artifactType == DocumentNodeArtifactType.SUMMARY
                || artifactType == DocumentNodeArtifactType.REVIEW_QUESTION_SET;
    }

    @Override
    public DocumentNodeArtifactGenerationResult generate(DocumentNodeArtifactGenerationContext context) {
        String prompt = switch (context.artifactType()) {
            case SUMMARY -> promptBuilder.buildSummaryPrompt(context.document(), context.node(), context.chunks());
            case REVIEW_QUESTION_SET -> promptBuilder.buildReviewQuestionPrompt(
                    context.document(),
                    context.node(),
                    context.chunks(),
                    context.minQuestionCount(),
                    context.maxQuestionCount()
            );
        };
        warnIfLargePrompt(prompt, context.node().getId(), context.node().getNodeType(), context.artifactType().name());
        AiChatCompletion completion = aiChatGateway.generate(prompt, 0.2, workloadFor(context.artifactType()));
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
            case PART_FALLBACK -> context.childSummaries().isEmpty()
                    ? promptBuilder.buildLeafSummaryPrompt(context.document(), context.node(), context.directChunks())
                    : promptBuilder.buildPartSummaryPrompt(context);
            case SECTION_FROM_SUBSECTIONS_AND_DIRECT_CHUNKS -> promptBuilder.buildSectionSummaryPrompt(context);
            case CHAPTER_FROM_SECTIONS -> promptBuilder.buildParentSummaryPrompt(context);
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
        try {
            return parse(context, rawResponse);
        } catch (InvalidDataException ex) {
            String repairPrompt = buildRepairPrompt(context, originalPrompt, rawResponse, ex.getMessage());
            String repairedResponse = aiChatGateway.generate(repairPrompt, 0.0, workloadFor(context.artifactType())).content();
            return parse(context, repairedResponse);
        }
    }

    private Map<String, Object> parse(DocumentNodeArtifactGenerationContext context, String rawResponse) {
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
            prompt.append("Rule hien thi: citations giu chunkId, nhung question/options/correctAnswer/answerExplanation khong duoc nhac chunkId/chunk.\n\n");
        }
        prompt.append("Schema bat buoc:\n");
        if (context.artifactType() == DocumentNodeArtifactType.SUMMARY) {
            prompt.append("""
                    {
                      "nodeTitle": "string",
                      "sectionPath": "string",
                      "nodeType": "string",
                      "summaryMode": "SUBSECTION_LEVEL2_FROM_CHUNKS|SUBSECTION_FROM_CHUNKS|SECTION_FROM_CHUNKS_FALLBACK|CHAPTER_FALLBACK|PART_FALLBACK",
                      "summary": "string",
                      "keyPoints": ["string"],
                      "childSummaries": [],
                      "childSummaryRefs": [],
                      "citations": [{"chunkId": 123, "pageFrom": 1, "pageTo": 2}],
                      "coverage": {
                        "expectedChildCount": 0,
                        "usedChildCount": 0,
                        "missingChildNodeIds": [],
                        "directChunkCount": 1,
                        "usedDirectChunkCount": 1,
                        "complete": true
                      }
                    }
                    """);
        } else {
            prompt.append("""
                    {
                      "nodeTitle": "string",
                      "sectionPath": "string",
                      "questionCount": 15,
                      "questions": [
                        {
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
        }
        prompt.append("\nOriginal prompt/context:\n<<<PROMPT>>>\n")
                .append(originalPrompt)
                .append("\n<<<END_PROMPT>>>\n");
        prompt.append("\nInvalid JSON/response:\n<<<RESPONSE>>>\n")
                .append(rawResponse)
                .append("\n<<<END_RESPONSE>>>\n");
        return prompt.toString();
    }

    private String buildSummaryRepairPrompt(SummaryGenerationContext context,
                                            String originalPrompt,
                                            String rawResponse,
                                            String validationError) {
        SummaryCoverage cov = context.coverage();
        int expChild = cov != null ? cov.expectedChildCount() : 0;
        int usedChild = cov != null ? cov.usedChildCount() : 0;
        int fallback = cov != null ? cov.fallbackChildCount() : 0;
        int directChunk = cov != null ? cov.directChunkCount() : 0;
        int usedDirect = cov != null ? cov.usedDirectChunkCount() : 0;

        StringBuilder prompt = new StringBuilder();
        prompt.append("Sua JSON summary bottom-up bi loi schema. Chi tra ve mot JSON object hop le, khong markdown/code fence.\n");
        prompt.append("Validation error: ").append(validationError).append("\n");
        prompt.append("Summary mode: ").append(context.summaryMode()).append("\n");
        prompt.append("Node title: ").append(context.node().getTitle()).append("\n");
        prompt.append("Section path: ").append(context.node().getSectionPath()).append("\n\n");
        prompt.append("Schema bat buoc (tat ca gia tri so va mang phai khop chinh xac voi cac gia tri sau):\n");
        prompt.append(String.format("""
                {
                  "nodeTitle": "string",
                  "sectionPath": "string",
                  "nodeType": "string",
                  "summaryMode": "%s",
                  "summary": "string",
                  "keyPoints": ["string"],
                  "childSummaries": %s,
                  "childSummaryRefs": %s,
                  "citations": [],
                  "coverage": {
                    "expectedChildCount": %d,
                    "usedChildCount": %d,
                    "fallbackChildCount": %d,
                    "missingChildNodeIds": [],
                    "directChunkCount": %d,
                    "usedDirectChunkCount": %d,
                    "complete": true
                  }
                }
                """,
                context.summaryMode().name(),
                buildChildSummariesSchema(context.childSummaries()),
                buildChildSummaryRefsSchema(context.childSummaries()),
                expChild, usedChild, fallback, directChunk, usedDirect
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

    private String buildChildSummaryRefsSchema(List<ChildSummary> childSummaries) {
        if (childSummaries == null || childSummaries.isEmpty()) {
            return "[]";
        }
        String entries = childSummaries.stream()
                .map(cs -> String.format(
                        "{\"nodeId\": %d, \"artifactId\": %d, \"sourceHash\": \"%s\"}",
                        cs.nodeId() != null ? cs.nodeId() : 0,
                        cs.artifactId() != null ? cs.artifactId() : 0,
                        cs.sourceHash() != null ? cs.sourceHash().replace("\"", "\\\"") : ""
                ))
                .collect(Collectors.joining(", "));
        return "[" + entries + "]";
    }

    private String buildChildSummariesSchema(List<ChildSummary> childSummaries) {
        if (childSummaries == null || childSummaries.isEmpty()) {
            return "[]";
        }
        String entries = childSummaries.stream()
                .map(cs -> String.format(
                        "{\"nodeId\": %d, \"summary\": \"<tom tat noi dung cua %s>\"}",
                        cs.nodeId() != null ? cs.nodeId() : 0,
                        cs.title() != null ? cs.title().replace("\"", "\\\"") : "node " + cs.nodeId()
                ))
                .collect(Collectors.joining(", "));
        return "[" + entries + "]";
    }

    private AiWorkload workloadFor(DocumentNodeArtifactType artifactType) {
        return artifactType == DocumentNodeArtifactType.SUMMARY
                ? AiWorkload.ENRICH_SUMMARY
                : AiWorkload.ENRICH_REVIEW_QUESTION;
    }
}
