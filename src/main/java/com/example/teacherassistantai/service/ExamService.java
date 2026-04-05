package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.ExamStatus;
import com.example.teacherassistantai.common.response.PageResponse;
import com.example.teacherassistantai.dto.request.AddExamQuestionsRequest;
import com.example.teacherassistantai.dto.request.CreateExamRequest;
import com.example.teacherassistantai.dto.request.UpdateExamQuestionRequest;
import com.example.teacherassistantai.dto.request.UpdateExamRequest;
import com.example.teacherassistantai.dto.response.AnswerOptionResponse;
import com.example.teacherassistantai.dto.response.ExamQuestionResponse;
import com.example.teacherassistantai.dto.response.ExamResponse;
import com.example.teacherassistantai.entity.*;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExamService {

    private final ExamRepository examRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final ClassroomRepository classroomRepository;
    private final QuestionRepository questionRepository;
    private final UserRepository userRepository;

    // ─────────────────────────────────────────────────────────────────────────
    // Exam CRUD
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public ExamResponse createExam(CreateExamRequest request) {
        Classroom classroom = classroomRepository.findById(request.getClassroomId())
                .orElseThrow(() -> new ResourceNotFoundException("Classroom not found: " + request.getClassroomId()));

        validateExamTimes(request.getStartTime(), request.getEndTime());

        User currentUser = getCurrentUser();

        Exam exam = Exam.builder()
                .title(request.getTitle().trim())
                .description(request.getDescription())
                .classroom(classroom)
                .createdBy(currentUser)
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .durationMinutes(request.getDurationMinutes())
                .totalScore(request.getTotalScore() != null ? request.getTotalScore() : 10.0)
                .passingScore(request.getPassingScore() != null ? request.getPassingScore() : 5.0)
                .shuffleQuestions(request.getShuffleQuestions() != null ? request.getShuffleQuestions() : false)
                .shuffleOptions(request.getShuffleOptions() != null ? request.getShuffleOptions() : false)
                .status(ExamStatus.DRAFT)
                .build();

        exam = examRepository.save(exam);
        log.info("Created exam: id={}, title={}", exam.getId(), exam.getTitle());
        return toResponse(exam, 0);
    }

    @Transactional(readOnly = true)
    public PageResponse<?> getAllExams(Long classroomId, ExamStatus status, int pageNo, int pageSize) {
        Page<Exam> page = examRepository.findByFilters(classroomId, status, PageRequest.of(pageNo, pageSize));
        List<ExamResponse> items = page.getContent().stream()
                .map(e -> toResponse(e, examQuestionRepository.countByExamId(e.getId())))
                .collect(Collectors.toList());
        return PageResponse.builder()
                .pageNo(pageNo)
                .pageSize(pageSize)
                .totalPage(page.getTotalPages())
                .items(items)
                .build();
    }

    @Transactional(readOnly = true)
    public ExamResponse getExamById(Long id) {
        Exam exam = findExamById(id);
        int questionCount = examQuestionRepository.countByExamId(id);
        return toResponse(exam, questionCount);
    }

    @Transactional
    public ExamResponse updateExam(Long id, UpdateExamRequest request) {
        Exam exam = findExamById(id);

        if (exam.getStatus() != ExamStatus.DRAFT) {
            throw new InvalidDataException("Only DRAFT exams can be updated");
        }

        if (request.getTitle() != null) exam.setTitle(request.getTitle().trim());
        if (request.getDescription() != null) exam.setDescription(request.getDescription());
        if (request.getStartTime() != null) exam.setStartTime(request.getStartTime());
        if (request.getEndTime() != null) exam.setEndTime(request.getEndTime());
        if (request.getDurationMinutes() != null) exam.setDurationMinutes(request.getDurationMinutes());
        if (request.getTotalScore() != null) exam.setTotalScore(request.getTotalScore());
        if (request.getPassingScore() != null) exam.setPassingScore(request.getPassingScore());
        if (request.getShuffleQuestions() != null) exam.setShuffleQuestions(request.getShuffleQuestions());
        if (request.getShuffleOptions() != null) exam.setShuffleOptions(request.getShuffleOptions());

        // Re-validate times if either was updated
        validateExamTimes(exam.getStartTime(), exam.getEndTime());

        exam = examRepository.save(exam);
        log.info("Updated exam: id={}", exam.getId());
        return toResponse(exam, examQuestionRepository.countByExamId(exam.getId()));
    }

    @Transactional
    public void deleteExam(Long id) {
        Exam exam = findExamById(id);
        if (exam.getStatus() != ExamStatus.DRAFT) {
            throw new InvalidDataException("Only DRAFT exams can be deleted");
        }
        examRepository.delete(exam);
        log.info("Deleted exam: id={}", id);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Exam Questions
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public List<ExamQuestionResponse> addQuestions(Long examId, AddExamQuestionsRequest request) {
        Exam exam = findExamById(examId);

        if (exam.getStatus() != ExamStatus.DRAFT) {
            throw new InvalidDataException("Cannot add questions to a non-DRAFT exam");
        }

        // Current max orderIndex to append new questions after existing ones
        int currentCount = examQuestionRepository.countByExamId(examId);

        List<ExamQuestion> toSave = new ArrayList<>();
        int orderIndex = currentCount;

        for (Long questionId : request.getQuestionIds()) {
            // Skip duplicates silently
            if (examQuestionRepository.existsByExamIdAndQuestionId(examId, questionId)) {
                log.warn("Question {} already exists in exam {}, skipping", questionId, examId);
                continue;
            }

            Question question = questionRepository.findById(questionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Question not found: " + questionId));

            // Validate question belongs to the same subject as the exam's classroom
            Long examSubjectId = exam.getClassroom().getSubject().getId();
            Long questionSubjectId = question.getQuestionBank().getSubject().getId();
            if (!examSubjectId.equals(questionSubjectId)) {
                throw new InvalidDataException(
                        "Question " + questionId + " does not belong to the same subject as the exam");
            }

            ExamQuestion examQuestion = ExamQuestion.builder()
                    .exam(exam)
                    .question(question)
                    .orderIndex(orderIndex++)
                    .score(request.getScorePerQuestion() != null ? request.getScorePerQuestion() : 1.0)
                    .build();

            toSave.add(examQuestion);
        }

        if (toSave.isEmpty()) {
            throw new InvalidDataException("No new questions to add (all provided questions already exist in this exam)");
        }

        List<ExamQuestion> saved = examQuestionRepository.saveAll(toSave);
        log.info("Added {} questions to exam id={}", saved.size(), examId);
        return saved.stream().map(this::toExamQuestionResponse).collect(Collectors.toList());
    }

    @Transactional
    public void removeQuestion(Long examId, Long examQuestionId) {
        Exam exam = findExamById(examId);

        if (exam.getStatus() != ExamStatus.DRAFT) {
            throw new InvalidDataException("Cannot remove questions from a non-DRAFT exam");
        }

        ExamQuestion examQuestion = examQuestionRepository.findByExamIdAndId(examId, examQuestionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ExamQuestion not found: examId=" + examId + ", examQuestionId=" + examQuestionId));

        examQuestionRepository.delete(examQuestion);

        // Reorder remaining questions
        List<ExamQuestion> remaining = examQuestionRepository.findByExamIdOrderByOrderIndex(examId);
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setOrderIndex(i);
        }
        examQuestionRepository.saveAll(remaining);
        log.info("Removed question examQuestionId={} from examId={}", examQuestionId, examId);
    }

    @Transactional(readOnly = true)
    public List<ExamQuestionResponse> getExamQuestions(Long examId) {
        findExamById(examId); // validate exam exists
        return examQuestionRepository.findByExamIdOrderByOrderIndex(examId)
                .stream()
                .map(this::toExamQuestionResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public ExamQuestionResponse updateExamQuestion(Long examId, Long examQuestionId, UpdateExamQuestionRequest request) {
        Exam exam = findExamById(examId);

        if (exam.getStatus() != ExamStatus.DRAFT) {
            throw new InvalidDataException("Cannot update questions in a non-DRAFT exam");
        }

        ExamQuestion examQuestion = examQuestionRepository.findByExamIdAndId(examId, examQuestionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ExamQuestion not found: examId=" + examId + ", examQuestionId=" + examQuestionId));

        if (request.getOrderIndex() != null) examQuestion.setOrderIndex(request.getOrderIndex());
        if (request.getScore() != null) examQuestion.setScore(request.getScore());

        examQuestion = examQuestionRepository.save(examQuestion);
        log.info("Updated examQuestion id={}", examQuestionId);
        return toExamQuestionResponse(examQuestion);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Exam findExamById(Long id) {
        return examRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Exam not found: " + id));
    }

    private User getCurrentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResourceNotFoundException("User not authenticated");
        }
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private void validateExamTimes(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime != null && endTime != null && !endTime.isAfter(startTime)) {
            throw new InvalidDataException("End time must be after start time");
        }
    }

    private ExamResponse toResponse(Exam exam, int questionCount) {
        return ExamResponse.builder()
                .id(exam.getId())
                .title(exam.getTitle())
                .description(exam.getDescription())
                .classroomId(exam.getClassroom().getId())
                .classroomName(exam.getClassroom().getName())
                .subjectId(exam.getClassroom().getSubject().getId())
                .subjectName(exam.getClassroom().getSubject().getName())
                .createdById(exam.getCreatedBy().getId())
                .createdByName(exam.getCreatedBy().getFullName())
                .startTime(exam.getStartTime())
                .endTime(exam.getEndTime())
                .durationMinutes(exam.getDurationMinutes())
                .totalScore(exam.getTotalScore())
                .passingScore(exam.getPassingScore())
                .shuffleQuestions(exam.getShuffleQuestions())
                .shuffleOptions(exam.getShuffleOptions())
                .status(exam.getStatus())
                .questionCount(questionCount)
                .createdAt(exam.getCreatedAt())
                .updatedAt(exam.getUpdatedAt())
                .build();
    }

    private ExamQuestionResponse toExamQuestionResponse(ExamQuestion eq) {
        List<AnswerOptionResponse> options = eq.getQuestion().getAnswerOptions().stream()
                .map(opt -> AnswerOptionResponse.builder()
                        .id(opt.getId())
                        .questionId(eq.getQuestion().getId())
                        .content(opt.getContent())
                        .isCorrect(opt.getIsCorrect())
                        .orderIndex(opt.getOrderIndex())
                        .build())
                .collect(Collectors.toList());

        return ExamQuestionResponse.builder()
                .id(eq.getId())
                .examId(eq.getExam().getId())
                .questionId(eq.getQuestion().getId())
                .questionContent(eq.getQuestion().getContent())
                .questionType(eq.getQuestion().getQuestionType())
                .difficultyLevel(eq.getQuestion().getDifficultyLevel())
                .orderIndex(eq.getOrderIndex())
                .score(eq.getScore())
                .answerOptions(options)
                .build();
    }
}


