package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.*;
import com.example.teacherassistantai.dto.request.SubmitAnswerRequest;
import com.example.teacherassistantai.dto.request.SubmitExamRequest;
import com.example.teacherassistantai.dto.response.*;
import com.example.teacherassistantai.entity.*;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubmissionService {

    private final ExamRepository examRepository;
    private final ExamSubmissionRepository submissionRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final StudentAnswerRepository studentAnswerRepository;
    private final ClassroomRepository classroomRepository;
    private final UserRepository userRepository;
    private final AnswerOptionRepository answerOptionRepository;

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 3: Publish / Cancel
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public ExamResponse publishExam(Long examId) {
        Exam exam = findExamById(examId);

        if (exam.getStatus() != ExamStatus.DRAFT) {
            throw new InvalidDataException("Only DRAFT exams can be published. Current status: " + exam.getStatus());
        }

        int questionCount = examQuestionRepository.countByExamId(examId);
        if (questionCount == 0) {
            throw new InvalidDataException("Cannot publish an exam with no questions. Please add at least one question.");
        }

        if (!exam.getStartTime().isAfter(LocalDateTime.now())) {
            throw new InvalidDataException("Start time must be in the future to publish the exam.");
        }

        exam.setStatus(ExamStatus.SCHEDULED);
        exam = examRepository.save(exam);
        log.info("Published exam id={}", examId);
        return toExamResponse(exam, questionCount);
    }

    @Transactional
    public ExamResponse cancelExam(Long examId) {
        Exam exam = findExamById(examId);

        if (exam.getStatus() == ExamStatus.ONGOING || exam.getStatus() == ExamStatus.FINISHED) {
            throw new InvalidDataException("Cannot cancel an exam that is " + exam.getStatus());
        }
        if (exam.getStatus() == ExamStatus.CANCELLED) {
            throw new InvalidDataException("Exam is already cancelled.");
        }

        exam.setStatus(ExamStatus.CANCELLED);
        exam = examRepository.save(exam);
        log.info("Cancelled exam id={}", examId);
        return toExamResponse(exam, examQuestionRepository.countByExamId(examId));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 3: Teacher xem submissions
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SubmissionSummaryResponse> getSubmissionsByExam(Long examId, SubmissionStatus status) {
        findExamById(examId); // validate exists
        return submissionRepository.findByExamIdAndStatus(examId, status)
                .stream()
                .map(this::toSummaryResponse)
                .collect(Collectors.toList());
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Phase 4: Student xem exam của lớp
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<StudentExamResponse> getExamsForStudent(Long classroomId, ExamStatus status) {
        User student = getCurrentUser();
        classroomRepository.findById(classroomId)
                .orElseThrow(() -> new ResourceNotFoundException("Classroom not found: " + classroomId));

        List<Exam> exams = examRepository
                .findByClassroomIdAndStatus(classroomId, status, org.springframework.data.domain.Pageable.unpaged())
                .getContent();

        return exams.stream().map(exam -> {
            String myStatus = submissionRepository.findByExamIdAndStudentId(exam.getId(), student.getId())
                    .map(s -> s.getStatus().name())
                    .orElse("NOT_STARTED");
            return toStudentExamResponse(exam, myStatus);
        }).collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 4: Bắt đầu làm bài
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public StartExamResponse startExam(Long examId) {
        User student = getCurrentUser();
        Exam exam = findExamById(examId);

        // Kiểm tra exam đã publish và đang trong thời gian thi
        validateExamIsAccessible(exam);

        // Kiểm tra student thuộc classroom
        if (!classroomRepository.isStudentInClassroom(exam.getClassroom().getId(), student.getId())) {
            throw new InvalidDataException("You are not enrolled in the classroom of this exam.");
        }

        // Kiểm tra chưa thi (chưa có submission)
        if (submissionRepository.existsByExamIdAndStudentId(examId, student.getId())) {
            throw new InvalidDataException("You have already started or submitted this exam.");
        }

        LocalDateTime now = LocalDateTime.now();
        ExamSubmission submission = ExamSubmission.builder()
                .exam(exam)
                .student(student)
                .startedAt(now)
                .status(SubmissionStatus.IN_PROGRESS)
                .totalScore(0.0)
                .build();
        submission = submissionRepository.save(submission);
        log.info("Student id={} started exam id={}, submissionId={}", student.getId(), examId, submission.getId());

        List<ExamQuestion> questions = examQuestionRepository.findByExamIdOrderByOrderIndex(examId);
        if (exam.getShuffleQuestions()) {
            questions = new ArrayList<>(questions);
            Collections.shuffle(questions);
        }

        List<ExamQuestionForStudentResponse> questionResponses = questions.stream()
                .map(eq -> toQuestionForStudent(eq, exam.getShuffleOptions()))
                .collect(Collectors.toList());

        return StartExamResponse.builder()
                .submissionId(submission.getId())
                .examId(exam.getId())
                .examTitle(exam.getTitle())
                .startedAt(now)
                .deadlineAt(now.plusMinutes(exam.getDurationMinutes()))
                .status(SubmissionStatus.IN_PROGRESS)
                .questions(questionResponses)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 4: Nộp bài + chấm tự động
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public SubmissionDetailResponse submitExam(Long submissionId, SubmitExamRequest request) {
        User student = getCurrentUser();
        ExamSubmission submission = findSubmissionById(submissionId);

        // Chỉ owner mới được nộp
        if (!submission.getStudent().getId().equals(student.getId())) {
            throw new InvalidDataException("You are not the owner of this submission.");
        }

        if (submission.getStatus() != SubmissionStatus.IN_PROGRESS) {
            throw new InvalidDataException("Exam has already been submitted.");
        }

        // Kiểm tra hết giờ — vẫn cho nộp nhưng ghi nhận
        Exam exam = submission.getExam();
        LocalDateTime deadline = submission.getStartedAt().plusMinutes(exam.getDurationMinutes());
        if (LocalDateTime.now().isAfter(deadline)) {
            log.warn("Submission id={} is past deadline but still accepted", submissionId);
        }

        // Lấy tất cả ExamQuestion của bài thi
        List<ExamQuestion> examQuestions = examQuestionRepository.findByExamIdOrderByOrderIndex(exam.getId());
        Map<Long, ExamQuestion> examQuestionMap = examQuestions.stream()
                .collect(Collectors.toMap(ExamQuestion::getId, eq -> eq));

        // Build map câu trả lời từ request
        Map<Long, SubmitAnswerRequest> answerMap = new HashMap<>();
        if (request.getAnswers() != null) {
            for (SubmitAnswerRequest ans : request.getAnswers()) {
                if (!examQuestionMap.containsKey(ans.getExamQuestionId())) {
                    throw new InvalidDataException("ExamQuestion id=" + ans.getExamQuestionId()
                            + " does not belong to this exam.");
                }
                answerMap.put(ans.getExamQuestionId(), ans);
            }
        }

        // Tạo StudentAnswer và chấm điểm tự động cho từng câu
        List<StudentAnswer> savedAnswers = new ArrayList<>();
        double totalScore = 0.0;

        for (ExamQuestion eq : examQuestions) {
            SubmitAnswerRequest ansReq = answerMap.get(eq.getId());
            StudentAnswer studentAnswer = buildAndGradeAnswer(submission, eq, ansReq);
            savedAnswers.add(studentAnswer);
            totalScore += studentAnswer.getScore();
        }

        studentAnswerRepository.saveAll(savedAnswers);

        // Cập nhật submission
        submission.setSubmittedAt(LocalDateTime.now());
        submission.setTotalScore(totalScore);
        submission.setStatus(SubmissionStatus.SUBMITTED);
        submissionRepository.save(submission);
        log.info("Submission id={} submitted. totalScore={}", submissionId, totalScore);

        return toDetailResponse(submission, savedAnswers, exam);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 4: Xem kết quả
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SubmissionDetailResponse getSubmissionDetail(Long submissionId) {
        User currentUser = getCurrentUser();
        ExamSubmission submission = findSubmissionById(submissionId);

        boolean isStudent = submission.getStudent().getId().equals(currentUser.getId());
        boolean isTeacherOrAdmin = currentUser.getRoles().stream()
                .anyMatch(r -> "TEACHER".equalsIgnoreCase(r.getName()) || "ADMIN".equalsIgnoreCase(r.getName()));

        if (!isStudent && !isTeacherOrAdmin) {
            throw new InvalidDataException("You do not have permission to view this submission.");
        }

        // Student chỉ được xem sau khi đã nộp
        if (isStudent && submission.getStatus() == SubmissionStatus.IN_PROGRESS) {
            throw new InvalidDataException("Exam is still in progress. Submit first to see results.");
        }

        List<StudentAnswer> answers = studentAnswerRepository.findBySubmissionId(submissionId);
        return toDetailResponse(submission, answers, submission.getExam());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers – chấm điểm tự động
    // ─────────────────────────────────────────────────────────────────────────

    private StudentAnswer buildAndGradeAnswer(ExamSubmission submission, ExamQuestion eq, SubmitAnswerRequest ansReq) {
        Question question = eq.getQuestion();
        QuestionType type = question.getQuestionType();
        double maxScore = eq.getScore();

        StudentAnswer.StudentAnswerBuilder builder = StudentAnswer.builder()
                .submission(submission)
                .examQuestion(eq)
                .score(0.0)
                .gradingStatus(GradingStatus.PENDING);

        if (ansReq == null) {
            // Câu bỏ trống
            return builder.build();
        }

        switch (type) {
            case MULTIPLE_CHOICE, TRUE_FALSE -> {
                if (ansReq.getSelectedOptionId() == null) return builder.build();
                AnswerOption selected = answerOptionRepository.findById(ansReq.getSelectedOptionId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "AnswerOption not found: " + ansReq.getSelectedOptionId()));
                // Kiểm tra option thuộc câu hỏi này
                if (!selected.getQuestion().getId().equals(question.getId())) {
                    throw new InvalidDataException("AnswerOption id=" + ansReq.getSelectedOptionId()
                            + " does not belong to question id=" + question.getId());
                }
                double score = Boolean.TRUE.equals(selected.getIsCorrect()) ? maxScore : 0.0;
                builder.selectedOption(selected).score(score).gradingStatus(GradingStatus.AUTO_GRADED);
            }
            case MULTI_SELECT -> {
                if (ansReq.getSelectedOptionIds() == null || ansReq.getSelectedOptionIds().isEmpty()) {
                    return builder.build();
                }
                List<AnswerOption> correctOptions = question.getAnswerOptions().stream()
                        .filter(AnswerOption::getIsCorrect).toList();
                Set<Long> correctIds = correctOptions.stream()
                        .map(AnswerOption::getId).collect(Collectors.toSet());
                Set<Long> selectedIds = new HashSet<>(ansReq.getSelectedOptionIds());

                double score = correctIds.equals(selectedIds) ? maxScore : 0.0;
                String idsJson = ansReq.getSelectedOptionIds().toString();
                builder.selectedOptionIds(idsJson).score(score).gradingStatus(GradingStatus.AUTO_GRADED);
            }
            case SHORT_ANSWER, ESSAY, FILL_IN_BLANK -> {
                builder.answerContent(ansReq.getAnswerContent()).gradingStatus(GradingStatus.PENDING);
            }
        }

        return builder.build();
    }

    private void validateExamIsAccessible(Exam exam) {
        LocalDateTime now = LocalDateTime.now();
        if (exam.getStatus() == ExamStatus.DRAFT) {
            throw new InvalidDataException("Exam has not been published yet.");
        }
        if (exam.getStatus() == ExamStatus.CANCELLED) {
            throw new InvalidDataException("Exam has been cancelled.");
        }
        if (exam.getStatus() == ExamStatus.FINISHED) {
            throw new InvalidDataException("Exam has already finished.");
        }
        if (now.isBefore(exam.getStartTime())) {
            throw new InvalidDataException("Exam has not started yet. Start time: " + exam.getStartTime());
        }
        if (now.isAfter(exam.getEndTime())) {
            throw new InvalidDataException("Exam has already ended.");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers – mapping
    // ─────────────────────────────────────────────────────────────────────────

    private ExamQuestionForStudentResponse toQuestionForStudent(ExamQuestion eq, boolean shuffleOptions) {
        List<AnswerOptionForStudentResponse> options = eq.getQuestion().getAnswerOptions().stream()
                .map(opt -> AnswerOptionForStudentResponse.builder()
                        .id(opt.getId())
                        .content(opt.getContent())
                        .orderIndex(opt.getOrderIndex())
                        .build())
                .collect(Collectors.toList());

        if (shuffleOptions) Collections.shuffle(options);

        return ExamQuestionForStudentResponse.builder()
                .examQuestionId(eq.getId())
                .orderIndex(eq.getOrderIndex())
                .content(eq.getQuestion().getContent())
                .questionType(eq.getQuestion().getQuestionType())
                .difficultyLevel(eq.getQuestion().getDifficultyLevel())
                .score(eq.getScore())
                .answerOptions(options)
                .build();
    }

    private SubmissionDetailResponse toDetailResponse(ExamSubmission submission,
                                                       List<StudentAnswer> answers,
                                                       Exam exam) {
        long graded = answers.stream()
                .filter(a -> a.getGradingStatus() != GradingStatus.PENDING).count();
        long pending = answers.stream()
                .filter(a -> a.getGradingStatus() == GradingStatus.PENDING).count();

        List<StudentAnswerDetailResponse> answerDetails = answers.stream()
                .map(this::toAnswerDetail)
                .collect(Collectors.toList());

        boolean passed = submission.getTotalScore() >= exam.getPassingScore();

        return SubmissionDetailResponse.builder()
                .submissionId(submission.getId())
                .examId(exam.getId())
                .examTitle(exam.getTitle())
                .studentId(submission.getStudent().getId())
                .studentName(submission.getStudent().getFullName())
                .studentEmail(submission.getStudent().getEmail())
                .startedAt(submission.getStartedAt())
                .submittedAt(submission.getSubmittedAt())
                .totalScore(submission.getTotalScore())
                .passingScore(exam.getPassingScore())
                .passed(passed)
                .status(submission.getStatus())
                .gradedQuestions((int) graded)
                .pendingQuestions((int) pending)
                .answers(answerDetails)
                .build();
    }

    private StudentAnswerDetailResponse toAnswerDetail(StudentAnswer sa) {
        ExamQuestion eq = sa.getExamQuestion();
        Question question = eq.getQuestion();

        // Parse selectedOptionIds từ JSON string "[1, 2, 3]"
        List<Long> selectedIds = null;
        if (sa.getSelectedOptionIds() != null && !sa.getSelectedOptionIds().isBlank()) {
            String raw = sa.getSelectedOptionIds().replaceAll("[\\[\\]\\s]", "");
            if (!raw.isEmpty()) {
                selectedIds = Arrays.stream(raw.split(","))
                        .map(Long::parseLong)
                        .collect(Collectors.toList());
            }
        }

        String selectedContent = sa.getSelectedOption() != null
                ? sa.getSelectedOption().getContent() : null;

        Boolean isCorrect = null;
        if (sa.getGradingStatus() == GradingStatus.AUTO_GRADED) {
            isCorrect = sa.getScore() > 0;
        }

        return StudentAnswerDetailResponse.builder()
                .examQuestionId(eq.getId())
                .questionContent(question.getContent())
                .questionType(question.getQuestionType())
                .maxScore(eq.getScore())
                .selectedOptionId(sa.getSelectedOption() != null ? sa.getSelectedOption().getId() : null)
                .selectedOptionContent(selectedContent)
                .selectedOptionIds(selectedIds)
                .answerContent(sa.getAnswerContent())
                .score(sa.getScore())
                .isCorrect(isCorrect)
                .gradingStatus(sa.getGradingStatus())
                .aiFeedback(sa.getAiFeedback())
                .build();
    }

    private SubmissionSummaryResponse toSummaryResponse(ExamSubmission s) {
        return SubmissionSummaryResponse.builder()
                .id(s.getId())
                .examId(s.getExam().getId())
                .examTitle(s.getExam().getTitle())
                .studentId(s.getStudent().getId())
                .studentName(s.getStudent().getFullName())
                .studentEmail(s.getStudent().getEmail())
                .startedAt(s.getStartedAt())
                .submittedAt(s.getSubmittedAt())
                .totalScore(s.getTotalScore())
                .status(s.getStatus())
                .build();
    }

    private StudentExamResponse toStudentExamResponse(Exam exam, String mySubmissionStatus) {
        return StudentExamResponse.builder()
                .id(exam.getId())
                .title(exam.getTitle())
                .description(exam.getDescription())
                .classroomId(exam.getClassroom().getId())
                .classroomName(exam.getClassroom().getName())
                .startTime(exam.getStartTime())
                .endTime(exam.getEndTime())
                .durationMinutes(exam.getDurationMinutes())
                .totalScore(exam.getTotalScore())
                .passingScore(exam.getPassingScore())
                .status(exam.getStatus())
                .questionCount(examQuestionRepository.countByExamId(exam.getId()))
                .mySubmissionStatus(mySubmissionStatus)
                .build();
    }

    private ExamResponse toExamResponse(Exam exam, int questionCount) {
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

    private Exam findExamById(Long id) {
        return examRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Exam not found: " + id));
    }

    private ExamSubmission findSubmissionById(Long id) {
        return submissionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Submission not found: " + id));
    }

    private User getCurrentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ResourceNotFoundException("User not authenticated");
        }
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}

