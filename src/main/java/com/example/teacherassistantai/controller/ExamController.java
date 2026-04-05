package com.example.teacherassistantai.controller;

import com.example.teacherassistantai.common.enumerate.ExamStatus;
import com.example.teacherassistantai.common.enumerate.SubmissionStatus;
import com.example.teacherassistantai.common.response.PageResponse;
import com.example.teacherassistantai.common.response.ResponseData;
import com.example.teacherassistantai.dto.request.AddExamQuestionsRequest;
import com.example.teacherassistantai.dto.request.CreateExamRequest;
import com.example.teacherassistantai.dto.request.UpdateExamQuestionRequest;
import com.example.teacherassistantai.dto.request.UpdateExamRequest;
import com.example.teacherassistantai.dto.response.ExamQuestionResponse;
import com.example.teacherassistantai.dto.response.ExamResponse;
import com.example.teacherassistantai.dto.response.StartExamResponse;
import com.example.teacherassistantai.dto.response.SubmissionSummaryResponse;
import com.example.teacherassistantai.service.ExamService;
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

import java.util.List;

@RestController
@RequestMapping("/exams")
@RequiredArgsConstructor
@Validated
@Slf4j
@Tag(name = "Exam Controller")
public class ExamController {

    private final ExamService examService;
    private final SubmissionService submissionService;

    // ─────────────────────────────────────────────────────────────────────────
    // Exam CRUD
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAnyAuthority('TEACHER', 'ADMIN')")
    @Operation(summary = "Create exam", description = "Create a new exam for a classroom (status = DRAFT)")
    public ResponseData<ExamResponse> createExam(@RequestBody @Valid CreateExamRequest request) {
        log.info("Request create exam: title={}, classroomId={}", request.getTitle(), request.getClassroomId());
        return new ResponseData<>(HttpStatus.CREATED.value(), "Exam created", examService.createExam(request));
    }

    @GetMapping
    @Operation(summary = "Get exams", description = "Get list of exams with optional filters")
    public ResponseData<PageResponse<?>> getExams(
            @RequestParam(required = false) Long classroomId,
            @RequestParam(required = false) ExamStatus status,
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "20") @Min(1) int pageSize) {
        log.info("Request get exams: classroomId={}, status={}", classroomId, status);
        return new ResponseData<>(HttpStatus.OK.value(), "Exams",
                examService.getAllExams(classroomId, status, pageNo, pageSize));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get exam detail")
    public ResponseData<ExamResponse> getExam(@PathVariable @Min(1) Long id) {
        log.info("Request get exam: id={}", id);
        return new ResponseData<>(HttpStatus.OK.value(), "Exam", examService.getExamById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'ADMIN')")
    @Operation(summary = "Update exam", description = "Update exam info (only allowed when status = DRAFT)")
    public ResponseData<ExamResponse> updateExam(
            @PathVariable @Min(1) Long id,
            @RequestBody @Valid UpdateExamRequest request) {
        log.info("Request update exam: id={}", id);
        return new ResponseData<>(HttpStatus.OK.value(), "Exam updated", examService.updateExam(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'ADMIN')")
    @Operation(summary = "Delete exam", description = "Delete exam (only allowed when status = DRAFT)")
    public ResponseData<Void> deleteExam(@PathVariable @Min(1) Long id) {
        log.info("Request delete exam: id={}", id);
        examService.deleteExam(id);
        return new ResponseData<>(HttpStatus.OK.value(), "Exam deleted");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Exam Questions
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/{examId}/questions")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'ADMIN')")
    @Operation(
            summary = "Add questions to exam (single/bulk)",
            description = "Add one or more questions from question bank. Duplicate questions are skipped automatically.")
    public ResponseData<List<ExamQuestionResponse>> addQuestions(
            @PathVariable @Min(1) Long examId,
            @RequestBody @Valid AddExamQuestionsRequest request) {
        log.info("Request add questions to exam: examId={}, count={}", examId, request.getQuestionIds().size());
        return new ResponseData<>(HttpStatus.CREATED.value(), "Questions added",
                examService.addQuestions(examId, request));
    }

    @GetMapping("/{examId}/questions")
    @Operation(summary = "Get exam questions", description = "Get all questions in an exam ordered by orderIndex")
    public ResponseData<List<ExamQuestionResponse>> getExamQuestions(@PathVariable @Min(1) Long examId) {
        log.info("Request get exam questions: examId={}", examId);
        return new ResponseData<>(HttpStatus.OK.value(), "Exam questions",
                examService.getExamQuestions(examId));
    }

    @PutMapping("/{examId}/questions/{examQuestionId}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'ADMIN')")
    @Operation(summary = "Update exam question", description = "Update score or orderIndex of a question in exam")
    public ResponseData<ExamQuestionResponse> updateExamQuestion(
            @PathVariable @Min(1) Long examId,
            @PathVariable @Min(1) Long examQuestionId,
            @RequestBody @Valid UpdateExamQuestionRequest request) {
        log.info("Request update exam question: examId={}, examQuestionId={}", examId, examQuestionId);
        return new ResponseData<>(HttpStatus.OK.value(), "Exam question updated",
                examService.updateExamQuestion(examId, examQuestionId, request));
    }

    @DeleteMapping("/{examId}/questions/{examQuestionId}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'ADMIN')")
    @Operation(summary = "Remove question from exam", description = "Remove a question from exam. Remaining questions are re-ordered automatically.")
    public ResponseData<Void> removeQuestion(
            @PathVariable @Min(1) Long examId,
            @PathVariable @Min(1) Long examQuestionId) {
        log.info("Request remove question: examId={}, examQuestionId={}", examId, examQuestionId);
        examService.removeQuestion(examId, examQuestionId);
        return new ResponseData<>(HttpStatus.OK.value(), "Question removed from exam");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 3: Publish / Cancel
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'ADMIN')")
    @Operation(
            summary = "Publish exam",
            description = "Chuyển trạng thái DRAFT → SCHEDULED. Yêu cầu: có ít nhất 1 câu hỏi, startTime trong tương lai.")
    public ResponseData<ExamResponse> publishExam(@PathVariable @Min(1) Long id) {
        log.info("Request publish exam: id={}", id);
        return new ResponseData<>(HttpStatus.OK.value(), "Exam published",
                submissionService.publishExam(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'ADMIN')")
    @Operation(
            summary = "Cancel exam",
            description = "Huỷ exam. Chỉ huỷ được khi trạng thái là DRAFT hoặc SCHEDULED.")
    public ResponseData<ExamResponse> cancelExam(@PathVariable @Min(1) Long id) {
        log.info("Request cancel exam: id={}", id);
        return new ResponseData<>(HttpStatus.OK.value(), "Exam cancelled",
                submissionService.cancelExam(id));
    }

    @GetMapping("/{examId}/submissions")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'ADMIN')")
    @Operation(
            summary = "Lấy danh sách submissions",
            description = "Teacher xem tất cả bài nộp của học sinh trong một kỳ thi.")
    public ResponseData<List<SubmissionSummaryResponse>> getSubmissions(
            @PathVariable @Min(1) Long examId,
            @RequestParam(required = false) SubmissionStatus status) {
        log.info("Request get submissions: examId={}, status={}", examId, status);
        return new ResponseData<>(HttpStatus.OK.value(), "Submissions",
                submissionService.getSubmissionsByExam(examId, status));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 4: Student bắt đầu làm bài
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/{examId}/start")
    @PreAuthorize("hasAuthority('STUDENT')")
    @Operation(
            summary = "Bắt đầu làm bài",
            description = "Student vào phòng thi, tạo submission và nhận đề. " +
                    "isCorrect bị ẩn hoàn toàn trong đề thi.")
    public ResponseData<StartExamResponse> startExam(@PathVariable @Min(1) Long examId) {
        log.info("Request start exam: examId={}", examId);
        return new ResponseData<>(HttpStatus.CREATED.value(), "Exam started",
                submissionService.startExam(examId));
    }
}

