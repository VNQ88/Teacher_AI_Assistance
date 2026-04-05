package com.example.teacherassistantai.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * Request để thêm nhiều câu hỏi cùng lúc vào một question bank.
 * Mỗi câu hỏi trong danh sách phải hợp lệ giống như khi tạo đơn lẻ.
 */
@Data
public class BulkCreateQuestionRequest {

    @NotEmpty(message = "Question list must not be empty")
    @Valid
    private List<CreateQuestionRequest> questions;
}

