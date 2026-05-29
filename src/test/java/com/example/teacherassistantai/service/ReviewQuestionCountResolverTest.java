package com.example.teacherassistantai.service;

import com.example.teacherassistantai.config.RagProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewQuestionCountResolverTest {

    @Test
    void resolve_returnsConfiguredRangesByNodeType() {
        ReviewQuestionCountResolver resolver = new ReviewQuestionCountResolver(new RagProperties());

        assertThat(resolver.resolve("subsection_level2")).isEqualTo(new ReviewQuestionCountResolver.CountRange(5, 10));
        assertThat(resolver.resolve("subsection")).isEqualTo(new ReviewQuestionCountResolver.CountRange(10, 15));
        assertThat(resolver.resolve("section")).isEqualTo(new ReviewQuestionCountResolver.CountRange(15, 20));
        assertThat(resolver.resolve("chapter")).isEqualTo(new ReviewQuestionCountResolver.CountRange(20, 25));
    }

    @Test
    void resolve_normalizesNodeTypeAndFallsBackToDefault() {
        ReviewQuestionCountResolver resolver = new ReviewQuestionCountResolver(new RagProperties());

        assertThat(resolver.resolve(" subsection-level2 ")).isEqualTo(new ReviewQuestionCountResolver.CountRange(5, 10));
        assertThat(resolver.resolve("unknown")).isEqualTo(new ReviewQuestionCountResolver.CountRange(15, 20));
    }

    @Test
    void resolve_rejectsInvalidRange() {
        RagProperties properties = new RagProperties();
        RagProperties.Enrichment.ReviewQuestionCountRange invalidRange =
                new RagProperties.Enrichment.ReviewQuestionCountRange();
        invalidRange.setMin(11);
        invalidRange.setMax(10);
        properties.getEnrichment().getReviewQuestionCounts().put("subsection_level2", invalidRange);
        ReviewQuestionCountResolver resolver = new ReviewQuestionCountResolver(properties);

        assertThatThrownBy(() -> resolver.resolve("subsection_level2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("min must be <= max");
    }
}
