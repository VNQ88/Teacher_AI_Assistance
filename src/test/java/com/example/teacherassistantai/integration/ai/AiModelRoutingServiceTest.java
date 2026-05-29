package com.example.teacherassistantai.integration.ai;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.config.RagProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiModelRoutingServiceTest {

    @Test
    void route_mapsWorkloadsToExpectedAccountAndModel() {
        RagProperties properties = new RagProperties();
        properties.getAi().setChatModel("rag-chat");
        properties.getAi().setEmbeddingModel("embedding-model");
        properties.getAi().getEnrichment().setBaseUrl("https://enrich.test");
        properties.getAi().getEnrichment().setApiKey("secret");
        properties.getAi().getEnrichment().setSummaryModel("summary-model");
        properties.getAi().getEnrichment().setReviewQuestionModel("question-model");
        AiModelRoutingService routingService = new AiModelRoutingService(properties);

        assertThat(routingService.route(AiWorkload.RAG_CHAT).accountAlias()).isEqualTo("RAG");
        assertThat(routingService.route(AiWorkload.RAG_CHAT).model()).isEqualTo("rag-chat");
        assertThat(routingService.route(AiWorkload.EMBEDDING).model()).isEqualTo("embedding-model");
        assertThat(routingService.route(AiWorkload.ENRICH_SUMMARY).accountAlias()).isEqualTo("ENRICHMENT");
        assertThat(routingService.route(AiWorkload.ENRICH_SUMMARY).model()).isEqualTo("summary-model");
        assertThat(routingService.route(AiWorkload.ENRICH_REVIEW_QUESTION).model()).isEqualTo("question-model");
    }

    @Test
    void route_onDemandReviewQuestion_usesRagAccountAndOnDemandModel() {
        RagProperties properties = new RagProperties();
        properties.getAi().setBaseUrl("https://rag.test");
        properties.getAi().setApiKey("rag-key");
        properties.getAi().getEnrichment().setBaseUrl("https://enrich.test");
        properties.getAi().getEnrichment().setApiKey("enrich-key");
        properties.getAi().getEnrichment().setReviewQuestionModel("bg-question-model");
        properties.getAi().getEnrichment().setOnDemandReviewQuestionModel("ondemand-question-model");
        AiModelRoutingService routingService = new AiModelRoutingService(properties);

        AiModelRoute route = routingService.route(AiWorkload.ENRICH_REVIEW_QUESTION_ONDEMAND);

        assertThat(route.accountAlias()).isEqualTo("RAG");
        assertThat(route.baseUrl()).isEqualTo("https://rag.test");
        assertThat(route.apiKey()).isEqualTo("rag-key");
        assertThat(route.model()).isEqualTo("ondemand-question-model");
        assertThat(route.workload()).isEqualTo(AiWorkload.RAG_CHAT);
        assertThat(route.enrichment()).isTrue();
    }

    @Test
    void enrichmentModelFor_mapsArtifactTypes() {
        RagProperties properties = new RagProperties();
        AiModelRoutingService routingService = new AiModelRoutingService(properties);

        assertThat(routingService.enrichmentModelFor(DocumentNodeArtifactType.SUMMARY))
                .isEqualTo("openai-gpt-5-mini");
        assertThat(routingService.enrichmentModelFor(DocumentNodeArtifactType.REVIEW_QUESTION_SET))
                .isEqualTo("openai-gpt-oss-120b");
    }

    @Test
    void reviewQuestionModelFor_picksOnDemandWhenFlagged() {
        RagProperties properties = new RagProperties();
        properties.getAi().getEnrichment().setReviewQuestionModel("bg-question-model");
        properties.getAi().getEnrichment().setOnDemandReviewQuestionModel("ondemand-question-model");
        AiModelRoutingService routingService = new AiModelRoutingService(properties);

        assertThat(routingService.reviewQuestionModelFor(false)).isEqualTo("bg-question-model");
        assertThat(routingService.reviewQuestionModelFor(true)).isEqualTo("ondemand-question-model");
    }
}
