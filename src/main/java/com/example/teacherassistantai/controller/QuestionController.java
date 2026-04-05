package com.example.teacherassistantai.controller;

import com.example.teacherassistantai.common.enumerate.DifficultyLevel;
import com.example.teacherassistantai.common.enumerate.QuestionType;
import com.example.teacherassistantai.common.response.PageResponse;
import com.example.teacherassistantai.common.response.ResponseData;
import com.example.teacherassistantai.dto.request.BulkCreateQuestionRequest;
import com.example.teacherassistantai.dto.request.CreateAnswerOptionRequest;
import com.example.teacherassistantai.dto.request.CreateQuestionRequest;
import com.example.teacherassistantai.dto.request.UpdateAnswerOptionRequest;
import com.example.teacherassistantai.dto.request.UpdateQuestionRequest;
import com.example.teacherassistantai.dto.response.AnswerOptionResponse;
import com.example.teacherassistantai.dto.response.QuestionResponse;
import com.example.teacherassistantai.service.AnswerOptionService;
import com.example.teacherassistantai.service.QuestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Validated
@Slf4j
@Tag(name = "Question Controller")
public class QuestionController {

    private final QuestionService questionService;
    private final AnswerOptionService answerOptionService;

    @PostMapping("/question-banks/{questionBankId}/questions")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'ADMIN')")
    @Operation(summary = "Create question", description = "Create a new question in a question bank")
    public ResponseData<QuestionResponse> createQuestion(
            @PathVariable @Min(1) Long questionBankId,
            @RequestBody @Valid CreateQuestionRequest request) {
        log.info("Request create question in question bank: questionBankId={}, type={}",
                questionBankId, request.getQuestionType());
        QuestionResponse response = questionService.createQuestion(questionBankId, request);
        return new ResponseData<>(HttpStatus.CREATED.value(), "Question created", response);
    }

    @PostMapping("/question-banks/{questionBankId}/questions/bulk")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'ADMIN')")
    @Operation(
            summary = "Bulk create questions",
            description = "Thêm nhiều câu hỏi (kèm đáp án) vào một question bank trong một lần gọi API. "
                    + "Toàn bộ danh sách được xử lý trong cùng một transaction: "
                    + "nếu bất kỳ câu hỏi nào không hợp lệ, tất cả sẽ bị rollback.")
    public ResponseData<List<QuestionResponse>> bulkCreateQuestions(
            @PathVariable @Min(1) Long questionBankId,
            @RequestBody @Valid BulkCreateQuestionRequest request) {
        log.info("Request bulk create questions: questionBankId={}, count={}",
                questionBankId, request.getQuestions().size());
        List<QuestionResponse> responses = questionService.bulkCreateQuestions(questionBankId, request);
        return new ResponseData<>(HttpStatus.CREATED.value(),
                "Created " + responses.size() + " questions", responses);
    }

    @GetMapping("/question-banks/{questionBankId}/questions")
    @Operation(summary = "Get questions", description = "Get list of questions in a question bank")
    public ResponseData<PageResponse<?>> getQuestions(
            @PathVariable @Min(1) Long questionBankId,
            @RequestParam(required = false) QuestionType questionType,
            @RequestParam(required = false) DifficultyLevel difficultyLevel,
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "20") @Min(1) int pageSize) {
        log.info("Request get questions: questionBankId={}, type={}, difficulty={}",
                questionBankId, questionType, difficultyLevel);
        PageResponse<?> response = questionService.getQuestionsByQuestionBank(
                questionBankId, questionType, difficultyLevel, pageNo, pageSize);
        return new ResponseData<>(HttpStatus.OK.value(), "Questions", response);
    }

    @GetMapping("/questions/{id}")
    @Operation(summary = "Get question detail", description = "Get question by ID")
    public ResponseData<QuestionResponse> getQuestion(@PathVariable @Min(1) Long id) {
        log.info("Request get question: id={}", id);
        QuestionResponse response = questionService.getQuestionById(id);
        return new ResponseData<>(HttpStatus.OK.value(), "Question", response);
    }

    @PutMapping("/questions/{id}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'ADMIN')")
    @Operation(summary = "Update question", description = "Update question information")
    public ResponseData<QuestionResponse> updateQuestion(
            @PathVariable @Min(1) Long id,
            @RequestBody @Valid UpdateQuestionRequest request) {
        log.info("Request update question: id={}", id);
        QuestionResponse response = questionService.updateQuestion(id, request);
        return new ResponseData<>(HttpStatus.OK.value(), "Question updated", response);
    }

    @DeleteMapping("/questions/{id}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'ADMIN')")
    @Operation(summary = "Delete question", description = "Delete a question")
    public ResponseData<Void> deleteQuestion(@PathVariable @Min(1) Long id) {
        log.info("Request delete question: id={}", id);
        questionService.deleteQuestion(id);
        return new ResponseData<>(HttpStatus.OK.value(), "Question deleted");
    }

    // ===== Answer Option Endpoints =====

    @PostMapping("/questions/{questionId}/answer-options")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'ADMIN')")
    @Operation(summary = "Create answer option", description = "Add a new answer option to a question")
    public ResponseData<AnswerOptionResponse> createAnswerOption(
            @PathVariable @Min(1) Long questionId,
            @RequestBody @Valid CreateAnswerOptionRequest request) {
        log.info("Request create answer option for question: questionId={}", questionId);
        AnswerOptionResponse response = answerOptionService.createAnswerOption(questionId, request);
        return new ResponseData<>(HttpStatus.CREATED.value(), "Answer option created", response);
    }

    @PutMapping("/answer-options/{id}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'ADMIN')")
    @Operation(summary = "Update answer option", description = "Update answer option information")
    public ResponseData<AnswerOptionResponse> updateAnswerOption(
            @PathVariable @Min(1) Long id,
            @RequestBody @Valid UpdateAnswerOptionRequest request) {
        log.info("Request update answer option: id={}", id);
        AnswerOptionResponse response = answerOptionService.updateAnswerOption(id, request);
        return new ResponseData<>(HttpStatus.OK.value(), "Answer option updated", response);
    }

    @DeleteMapping("/answer-options/{id}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'ADMIN')")
    @Operation(summary = "Delete answer option", description = "Delete an answer option")
    public ResponseData<Void> deleteAnswerOption(@PathVariable @Min(1) Long id) {
        log.info("Request delete answer option: id={}", id);
        answerOptionService.deleteAnswerOption(id);
        return new ResponseData<>(HttpStatus.OK.value(), "Answer option deleted");
    }
}


