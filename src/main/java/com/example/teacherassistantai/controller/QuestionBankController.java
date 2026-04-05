package com.example.teacherassistantai.controller;

import com.example.teacherassistantai.common.response.PageResponse;
import com.example.teacherassistantai.common.response.ResponseData;
import com.example.teacherassistantai.dto.request.CreateQuestionBankRequest;
import com.example.teacherassistantai.dto.request.UpdateQuestionBankRequest;
import com.example.teacherassistantai.dto.response.QuestionBankResponse;
import com.example.teacherassistantai.service.QuestionBankService;
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

@RestController
@RequestMapping("/question-banks")
@RequiredArgsConstructor
@Validated
@Slf4j
@Tag(name = "Question Bank Controller")
public class QuestionBankController {

    private final QuestionBankService questionBankService;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('TEACHER', 'ADMIN')")
    @Operation(summary = "Create question bank", description = "Create a new question bank for a subject")
    public ResponseData<QuestionBankResponse> createQuestionBank(@RequestBody @Valid CreateQuestionBankRequest request) {
        log.info("Request create question bank: title={}", request.getTitle());
        QuestionBankResponse response = questionBankService.createQuestionBank(request);
        return new ResponseData<>(HttpStatus.CREATED.value(), "Question bank created", response);
    }

    @GetMapping
    @Operation(summary = "Get question banks", description = "Get list of question banks with optional filters")
    public ResponseData<PageResponse<?>> getQuestionBanks(
            @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) Boolean published,
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "20") @Min(1) int pageSize) {
        log.info("Request get question banks: subjectId={}, published={}, pageNo={}, pageSize={}",
                subjectId, published, pageNo, pageSize);
        PageResponse<?> response = questionBankService.getAllQuestionBanks(subjectId, published, pageNo, pageSize);
        return new ResponseData<>(HttpStatus.OK.value(), "Question banks", response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get question bank detail", description = "Get question bank by ID")
    public ResponseData<QuestionBankResponse> getQuestionBank(@PathVariable @Min(1) Long id) {
        log.info("Request get question bank: id={}", id);
        QuestionBankResponse response = questionBankService.getQuestionBankById(id);
        return new ResponseData<>(HttpStatus.OK.value(), "Question bank", response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'ADMIN')")
    @Operation(summary = "Update question bank", description = "Update question bank information")
    public ResponseData<QuestionBankResponse> updateQuestionBank(
            @PathVariable @Min(1) Long id,
            @RequestBody @Valid UpdateQuestionBankRequest request) {
        log.info("Request update question bank: id={}", id);
        QuestionBankResponse response = questionBankService.updateQuestionBank(id, request);
        return new ResponseData<>(HttpStatus.OK.value(), "Question bank updated", response);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'ADMIN')")
    @Operation(summary = "Delete question bank", description = "Delete a question bank")
    public ResponseData<Void> deleteQuestionBank(@PathVariable @Min(1) Long id) {
        log.info("Request delete question bank: id={}", id);
        questionBankService.deleteQuestionBank(id);
        return new ResponseData<>(HttpStatus.OK.value(), "Question bank deleted");
    }
}


