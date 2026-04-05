package com.example.teacherassistantai.controller;

import com.example.teacherassistantai.common.response.ResponseData;
import com.example.teacherassistantai.dto.request.SubmitExamRequest;
import com.example.teacherassistantai.dto.response.SubmissionDetailResponse;
import com.example.teacherassistantai.service.SubmissionService;
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
@RequestMapping("/submissions")
@RequiredArgsConstructor
@Validated
@Slf4j
@Tag(name = "Submission Controller")
public class SubmissionController {

    private final SubmissionService submissionService;

    @PostMapping("/{submissionId}/submit")
    @PreAuthorize("hasAuthority('STUDENT')")
    @Operation(
            summary = "Nộp bài thi",
            description = "Student nộp bài kèm toàn bộ câu trả lời. " +
                    "MCQ và TRUE_FALSE được chấm tự động ngay lập tức. " +
                    "ESSAY và SHORT_ANSWER để trạng thái PENDING.")
    public ResponseData<SubmissionDetailResponse> submitExam(
            @PathVariable @Min(1) Long submissionId,
            @RequestBody @Valid SubmitExamRequest request) {
        log.info("Request submit exam: submissionId={}", submissionId);
        return new ResponseData<>(HttpStatus.OK.value(), "Submitted successfully",
                submissionService.submitExam(submissionId, request));
    }

    @GetMapping("/{submissionId}")
    @Operation(
            summary = "Xem kết quả bài thi",
            description = "Student xem kết quả bài đã nộp. Teacher/Admin xem kết quả của bất kỳ submission nào.")
    public ResponseData<SubmissionDetailResponse> getSubmissionDetail(
            @PathVariable @Min(1) Long submissionId) {
        log.info("Request get submission detail: submissionId={}", submissionId);
        return new ResponseData<>(HttpStatus.OK.value(), "Submission detail",
                submissionService.getSubmissionDetail(submissionId));
    }
}



