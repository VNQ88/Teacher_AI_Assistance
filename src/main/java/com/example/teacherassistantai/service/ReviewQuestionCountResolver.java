package com.example.teacherassistantai.service;

import com.example.teacherassistantai.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReviewQuestionCountResolver {

    private final RagProperties ragProperties;

    public CountRange resolve(String nodeType) {
        RagProperties.Enrichment enrichment = ragProperties.getEnrichment();
        String normalizedNodeType = normalize(nodeType);
        RagProperties.Enrichment.ReviewQuestionCountRange configured = null;
        for (Map.Entry<String, RagProperties.Enrichment.ReviewQuestionCountRange> entry :
                enrichment.getReviewQuestionCounts().entrySet()) {
            if (normalize(entry.getKey()).equals(normalizedNodeType)) {
                configured = entry.getValue();
                break;
            }
        }
        CountRange range = configured == null
                ? new CountRange(
                        enrichment.getDefaultReviewQuestionMinCount(),
                        enrichment.getDefaultReviewQuestionMaxCount()
                )
                : new CountRange(configured.getMin(), configured.getMax());
        validate(normalizedNodeType, range);
        return range;
    }

    private void validate(String nodeType, CountRange range) {
        if (range.min() < 1 || range.max() < 1) {
            throw new IllegalStateException(
                    "Invalid review question count range for nodeType=%s: min and max must be >= 1".formatted(nodeType)
            );
        }
        if (range.min() > range.max()) {
            throw new IllegalStateException(
                    "Invalid review question count range for nodeType=%s: min must be <= max".formatted(nodeType)
            );
        }
    }

    private String normalize(String nodeType) {
        if (!StringUtils.hasText(nodeType)) {
            return "";
        }
        return nodeType.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    public record CountRange(int min, int max) {
    }
}
