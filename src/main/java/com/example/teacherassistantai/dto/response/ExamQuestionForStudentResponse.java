package com.example.teacherassistantai.dto.response;

import com.example.teacherassistantai.common.enumerate.DifficultyLevel;
import com.example.teacherassistantai.common.enumerate.QuestionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Câu hỏi trong đề thi gửi cho Student khi bắt đầu làm bài.
 * isCorrect bị ẩn hoàn toàn — chỉ trả về sau khi nộp bài.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamQuestionForStudentResponse {
    private Long examQuestionId;
    private Integer orderIndex;
    private String content;
    private QuestionType questionType;
    private DifficultyLevel difficultyLevel;
    private Double score;
    // answerOptions KHÔNG có trường isCorrect
    private List<AnswerOptionForStudentResponse> answerOptions;
}

