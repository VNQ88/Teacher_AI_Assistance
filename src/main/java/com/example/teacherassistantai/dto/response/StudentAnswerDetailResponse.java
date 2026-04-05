package com.example.teacherassistantai.dto.response;

import com.example.teacherassistantai.common.enumerate.GradingStatus;
import com.example.teacherassistantai.common.enumerate.QuestionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Chi tiết một câu trả lời trong kết quả bài thi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentAnswerDetailResponse {
    private Long examQuestionId;
    private String questionContent;
    private QuestionType questionType;
    private Double maxScore;

    // Câu trả lời của student
    private Long selectedOptionId;
    private String selectedOptionContent;
    private List<Long> selectedOptionIds;
    private String answerContent;

    // Kết quả chấm
    private Double score;
    private Boolean isCorrect;          // null nếu chưa chấm (ESSAY, SHORT_ANSWER)
    private GradingStatus gradingStatus;
    private String aiFeedback;
}

