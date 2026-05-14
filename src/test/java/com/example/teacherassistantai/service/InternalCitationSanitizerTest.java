package com.example.teacherassistantai.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InternalCitationSanitizerTest {

    private final InternalCitationSanitizer sanitizer = new InternalCitationSanitizer();

    @Test
    void sanitize_removesSingleChunkReference() {
        assertThat(sanitizer.sanitize("Nội dung này dựa trên tài liệu (chunk 123)."))
                .isEqualTo("Nội dung này dựa trên tài liệu.");
    }

    @Test
    void sanitize_removesMultipleChunkReference() {
        assertThat(sanitizer.sanitize("Kết luận này có trong tài liệu (chunks 123, 124)."))
                .isEqualTo("Kết luận này có trong tài liệu.");
    }

    @Test
    void sanitize_removesChunkIdReference() {
        assertThat(sanitizer.sanitize("Theo chunkId: 123 nội dung này là đúng."))
                .isEqualTo("Theo nội dung này là đúng.");
    }

    @Test
    void sanitize_keepsNormalVietnameseText() {
        assertThat(sanitizer.sanitize("Đây là nội dung tiếng Việt bình thường, không có mã nội bộ."))
                .isEqualTo("Đây là nội dung tiếng Việt bình thường, không có mã nội bộ.");
    }
}
