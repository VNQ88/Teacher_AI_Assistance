package com.example.teacherassistantai.dto.request;

import jakarta.validation.Valid;
import lombok.Data;

import java.util.List;

/**
 * Nộp bài thi: gửi kèm toàn bộ câu trả lời.
 * answers có thể thiếu một số câu → những câu vắng mặt tính điểm 0.
 */
@Data
public class SubmitExamRequest {

    @Valid
    private List<SubmitAnswerRequest> answers;
}

