package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.integration.ai.AiChatGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class LlmDocumentNodeArtifactGenerator implements DocumentNodeArtifactGenerator {

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
        String rawResponse = aiChatGateway.generateAnswer(prompt, 0.2);
        Map<String, Object> content = parseOrRepair(context, prompt, rawResponse);
        return new DocumentNodeArtifactGenerationResult(
                content,
                estimateTokenCount(prompt, rawResponse)
        );
    }

    @Override
    public boolean supportsSummaryMode(SummaryMode summaryMode) {
        return summaryMode != null;
    }

    @Override
    public DocumentNodeArtifactGenerationResult generateSummary(SummaryGenerationContext context) {
        String prompt = switch (context.summaryMode()) {
            case SUBSECTION_FROM_CHUNKS, SECTION_FROM_CHUNKS_FALLBACK ->
                    promptBuilder.buildLeafSummaryPrompt(context.document(), context.node(), context.directChunks());
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
        String rawResponse = aiChatGateway.generateAnswer(prompt, 0.2);
        Map<String, Object> content = parseSummaryOrRepair(context, prompt, rawResponse);
        return new DocumentNodeArtifactGenerationResult(
                content,
                estimateTokenCount(prompt, rawResponse)
        );
    }

    private Map<String, Object> parseOrRepair(DocumentNodeArtifactGenerationContext context,
                                              String originalPrompt,
                                              String rawResponse) {
        try {
            return parse(context, rawResponse);
        } catch (InvalidDataException ex) {
            String repairPrompt = buildRepairPrompt(context, originalPrompt, rawResponse, ex.getMessage());
            String repairedResponse = aiChatGateway.generateAnswer(repairPrompt, 0.0);
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
            String repairedResponse = aiChatGateway.generateAnswer(repairPrompt, 0.0);
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
        prompt.append("Schema bat buoc:\n");
        if (context.artifactType() == DocumentNodeArtifactType.SUMMARY) {
            prompt.append("""
                    {
                      "nodeTitle": "string",
                      "sectionPath": "string",
                      "nodeType": "string",
                      "summaryMode": "SUBSECTION_FROM_CHUNKS|SECTION_FROM_CHUNKS_FALLBACK|CHAPTER_FALLBACK|PART_FALLBACK",
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
        StringBuilder prompt = new StringBuilder();
        prompt.append("Sua JSON summary bottom-up bi loi schema. Chi tra ve mot JSON object hop le, khong markdown/code fence.\n");
        prompt.append("Validation error: ").append(validationError).append("\n");
        prompt.append("Summary mode: ").append(context.summaryMode()).append("\n");
        prompt.append("Node title: ").append(context.node().getTitle()).append("\n");
        prompt.append("Section path: ").append(context.node().getSectionPath()).append("\n\n");
        prompt.append("""
                Schema bat buoc:
                {
                  "nodeTitle": "string",
                  "sectionPath": "string",
                  "nodeType": "string",
                  "summaryMode": "string",
                  "summary": "string",
                  "keyPoints": ["string"],
                  "childSummaries": [],
                  "childSummaryRefs": [],
                  "citations": [],
                  "coverage": {
                    "expectedChildCount": 0,
                    "usedChildCount": 0,
                    "missingChildNodeIds": [],
                    "directChunkCount": 0,
                    "usedDirectChunkCount": 0,
                    "complete": true
                  }
                }
                """);
        prompt.append("\nOriginal prompt/context:\n<<<PROMPT>>>\n")
                .append(originalPrompt)
                .append("\n<<<END_PROMPT>>>\n");
        prompt.append("\nInvalid JSON/response:\n<<<RESPONSE>>>\n")
                .append(rawResponse)
                .append("\n<<<END_RESPONSE>>>\n");
        return prompt.toString();
    }

    private Integer estimateTokenCount(String prompt, String response) {
        int chars = (prompt == null ? 0 : prompt.length()) + (response == null ? 0 : response.length());
        return Math.max(1, chars / 4);
    }
}
