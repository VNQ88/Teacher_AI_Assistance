package com.example.teacherassistantai.dto.response;

import com.example.teacherassistantai.common.enumerate.DifficultyLevel;
import com.example.teacherassistantai.common.enumerate.QuestionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamQuestionResponse {
    private Long id;          // ExamQuestion.id
    private Long examId;
    private Long questionId;
    private String questionContent;
    private QuestionType questionType;
    private DifficultyLevel difficultyLevel;
    private Integer orderIndex;
    private Double score;
    private List<AnswerOptionResponse> answerOptions;
}

