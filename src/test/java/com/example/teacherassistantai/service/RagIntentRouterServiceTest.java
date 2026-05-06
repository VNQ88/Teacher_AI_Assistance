package com.example.teacherassistantai.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagIntentRouterServiceTest {

    private final RagIntentRouterService routerService = new RagIntentRouterService();

    @Test
    void route_detectsSummaryIntentWithVietnameseAccents() {
        assertThat(routerService.route("Tóm tắt Chương 2 giúp tôi"))
                .isEqualTo(RagChatIntent.SECTION_SUMMARY);
    }

    @Test
    void route_detectsReviewQuestionIntent() {
        assertThat(routerService.route("Tạo bộ câu hỏi ôn tập chương 1 gồm trắc nghiệm và đúng sai"))
                .isEqualTo(RagChatIntent.REVIEW_QUESTION_GENERATION);
    }

    @Test
    void route_defaultsToFactualQa() {
        assertThat(routerService.route("Khái niệm vật chất là gì?"))
                .isEqualTo(RagChatIntent.FACTUAL_QA);
    }
}
