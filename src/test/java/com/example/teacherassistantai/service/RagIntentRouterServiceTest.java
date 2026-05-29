package com.example.teacherassistantai.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RagIntentRouterServiceTest {

    private final RagIntentRouterService routerService = new RagIntentRouterService();

    @Test
    void route_detectsSummaryIntentWithVietnameseAccents() {
        assertThat(routerService.route("Tóm tắt Chương 2 giúp tôi"))
                .isEqualTo(RagChatIntent.SECTION_SUMMARY);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Cho tôi nội dung chính của Chương 2",
            "Tổng kết phần này giúp tôi",
            "Chương này nói về điều gì?",
            "Give me a chapter 1 overview"
    })
    void route_detectsSummaryIntentVariants(String question) {
        assertThat(routerService.route(question))
                .isEqualTo(RagChatIntent.SECTION_SUMMARY);
    }

    @Test
    void route_detectsReviewQuestionIntent() {
        assertThat(routerService.route("Tạo bộ câu hỏi ôn tập chương 1 gồm trắc nghiệm và đúng sai"))
                .isEqualTo(RagChatIntent.REVIEW_QUESTION_GENERATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Soạn đề kiểm tra Chương 1",
            "Cho tôi 5 câu trắc nghiệm Chương 3",
            "Ra vài câu đúng sai cho mục này",
            "Make a practice quiz for chapter 2"
    })
    void route_detectsReviewQuestionIntentVariants(String question) {
        assertThat(routerService.route(question))
                .isEqualTo(RagChatIntent.REVIEW_QUESTION_GENERATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Mục lục tài liệu",
            "Cấu trúc giáo trình",
            "Tài liệu gồm những chương nào?",
            "Môn học gồm những phần nào?",
            "Danh sách chương trong tài liệu",
            "Phần I gồm những chương nào?"
    })
    void route_detectsDocumentOutlineIntentVariants(String question) {
        assertThat(routerService.route(question))
                .isEqualTo(RagChatIntent.DOCUMENT_OUTLINE);
    }

    @Test
    void route_outlineWordsTakePriorityOverSummaryWords() {
        assertThat(routerService.route("Tóm tắt cấu trúc tài liệu"))
                .isEqualTo(RagChatIntent.DOCUMENT_OUTLINE);
    }

    @Test
    void route_contentSummaryStillUsesSectionSummary() {
        assertThat(routerService.route("Tóm tắt nội dung môn học"))
                .isEqualTo(RagChatIntent.SECTION_SUMMARY);
    }

    @Test
    void route_defaultsToFactualQa() {
        assertThat(routerService.route("Khái niệm vật chất là gì?"))
                .isEqualTo(RagChatIntent.FACTUAL_QA);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Trắc nghiệm là gì?",
            "Tóm tắt là gì?",
            "Mục lục là gì?",
            "Cấu trúc là gì?",
            "Multiple choice là gì?"
    })
    void route_keepsDefinitionQuestionsAsFactualQa(String question) {
        assertThat(routerService.route(question))
                .isEqualTo(RagChatIntent.FACTUAL_QA);
    }
}
