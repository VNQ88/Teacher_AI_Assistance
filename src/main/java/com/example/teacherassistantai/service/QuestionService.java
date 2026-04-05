package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DifficultyLevel;
import com.example.teacherassistantai.common.enumerate.QuestionType;
import com.example.teacherassistantai.common.response.PageResponse;
import com.example.teacherassistantai.dto.request.BulkCreateQuestionRequest;
import com.example.teacherassistantai.dto.request.CreateAnswerOptionRequest;
import com.example.teacherassistantai.dto.request.CreateQuestionRequest;
import com.example.teacherassistantai.dto.request.UpdateQuestionRequest;
import com.example.teacherassistantai.dto.response.AnswerOptionResponse;
import com.example.teacherassistantai.dto.response.QuestionResponse;
import com.example.teacherassistantai.entity.AnswerOption;
import com.example.teacherassistantai.entity.Question;
import com.example.teacherassistantai.entity.QuestionBank;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.repository.AnswerOptionRepository;
import com.example.teacherassistantai.repository.QuestionBankRepository;
import com.example.teacherassistantai.repository.QuestionRepository;
import com.example.teacherassistantai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuestionService {

    private final QuestionRepository questionRepository;
    private final QuestionBankRepository questionBankRepository;
    private final AnswerOptionRepository answerOptionRepository;
    private final UserRepository userRepository;

    @Transactional
    public QuestionResponse createQuestion(Long questionBankId, CreateQuestionRequest request) {
        QuestionBank questionBank = questionBankRepository.findById(questionBankId)
                .orElseThrow(() -> new ResourceNotFoundException("Question bank not found with id: " + questionBankId));

        // Check permission
        validateQuestionBankOwnership(questionBank);

        // Validate answer options based on question type
        validateAnswerOptions(request.getQuestionType(), request.getAnswerOptions());

        Question question = Question.builder()
                .questionBank(questionBank)
                .content(request.getContent().trim())
                .questionType(request.getQuestionType())
                .difficultyLevel(request.getDifficultyLevel() != null ? request.getDifficultyLevel() : DifficultyLevel.MEDIUM)
                .explanation(request.getExplanation())
                .tags(request.getTags())
                .isAiGenerated(false)
                .answerOptions(new ArrayList<>())
                .build();

        question = questionRepository.save(question);

        // Create answer options
        for (CreateAnswerOptionRequest optionReq : request.getAnswerOptions()) {
            AnswerOption option = AnswerOption.builder()
                    .question(question)
                    .content(optionReq.getContent().trim())
                    .isCorrect(optionReq.getIsCorrect())
                    .orderIndex(optionReq.getOrderIndex())
                    .build();
            question.getAnswerOptions().add(option);
        }

        question = questionRepository.save(question);
        log.info("Created question: id={}, type={}", question.getId(), question.getQuestionType());

        return toResponse(question);
    }

    @Transactional
    public List<QuestionResponse> bulkCreateQuestions(Long questionBankId, BulkCreateQuestionRequest request) {
        QuestionBank questionBank = questionBankRepository.findById(questionBankId)
                .orElseThrow(() -> new ResourceNotFoundException("Question bank not found with id: " + questionBankId));

        validateQuestionBankOwnership(questionBank);

        List<QuestionResponse> results = new ArrayList<>();

        for (CreateQuestionRequest questionReq : request.getQuestions()) {
            validateAnswerOptions(questionReq.getQuestionType(), questionReq.getAnswerOptions());

            Question question = Question.builder()
                    .questionBank(questionBank)
                    .content(questionReq.getContent().trim())
                    .questionType(questionReq.getQuestionType())
                    .difficultyLevel(questionReq.getDifficultyLevel() != null
                            ? questionReq.getDifficultyLevel()
                            : DifficultyLevel.MEDIUM)
                    .explanation(questionReq.getExplanation())
                    .tags(questionReq.getTags())
                    .isAiGenerated(false)
                    .answerOptions(new ArrayList<>())
                    .build();

            question = questionRepository.save(question);

            for (CreateAnswerOptionRequest optionReq : questionReq.getAnswerOptions()) {
                AnswerOption option = AnswerOption.builder()
                        .question(question)
                        .content(optionReq.getContent().trim())
                        .isCorrect(optionReq.getIsCorrect())
                        .orderIndex(optionReq.getOrderIndex())
                        .build();
                question.getAnswerOptions().add(option);
            }

            question = questionRepository.save(question);
            results.add(toResponse(question));
        }

        log.info("Bulk created {} questions in question bank id={}", results.size(), questionBankId);
        return results;
    }

    @Transactional(readOnly = true)
    public PageResponse<?> getQuestionsByQuestionBank(
            Long questionBankId, QuestionType questionType, DifficultyLevel difficultyLevel, int pageNo, int pageSize) {

        // Verify question bank exists
        questionBankRepository.findById(questionBankId)
                .orElseThrow(() -> new ResourceNotFoundException("Question bank not found with id: " + questionBankId));

        Page<Question> page = questionRepository.findByQuestionBankAndFilters(
                questionBankId, questionType, difficultyLevel, PageRequest.of(pageNo, pageSize)
        );

        List<QuestionResponse> responses = page.getContent().stream()
                .map(this::toResponse)
                .toList();

        return new PageResponse<>(pageNo, pageSize, page.getTotalPages(), responses);
    }

    @Transactional(readOnly = true)
    public QuestionResponse getQuestionById(Long id) {
        Question question = findQuestion(id);
        return toResponse(question);
    }

    @Transactional
    public QuestionResponse updateQuestion(Long id, UpdateQuestionRequest request) {
        Question question = findQuestion(id);

        // Check permission
        validateQuestionBankOwnership(question.getQuestionBank());

        question.setContent(request.getContent().trim());
        question.setQuestionType(request.getQuestionType());
        if (request.getDifficultyLevel() != null) {
            question.setDifficultyLevel(request.getDifficultyLevel());
        }
        question.setExplanation(request.getExplanation());
        question.setTags(request.getTags());

        question = questionRepository.save(question);
        log.info("Updated question: id={}", question.getId());

        return toResponse(question);
    }

    @Transactional
    public void deleteQuestion(Long id) {
        Question question = findQuestion(id);

        // Check permission
        validateQuestionBankOwnership(question.getQuestionBank());

        questionRepository.delete(question);
        log.info("Deleted question: id={}", id);
    }

    private Question findQuestion(Long id) {
        return questionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Question not found with id: " + id));
    }

    private void validateQuestionBankOwnership(QuestionBank questionBank) {
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        boolean isAdmin = currentUser.getRoles().stream()
                .anyMatch(role -> "ADMIN".equalsIgnoreCase(role.getName()));

        if (!isAdmin && !questionBank.getCreatedBy().getId().equals(currentUser.getId())) {
            throw new InvalidDataException("You don't have permission to modify this question");
        }
    }

    private void validateAnswerOptions(QuestionType questionType, List<CreateAnswerOptionRequest> options) {
        if (options == null || options.isEmpty()) {
            throw new InvalidDataException("At least one answer option is required");
        }

        long correctCount = options.stream().filter(CreateAnswerOptionRequest::getIsCorrect).count();

        switch (questionType) {
            case MULTIPLE_CHOICE:
            case TRUE_FALSE:
                if (options.size() < 2) {
                    throw new InvalidDataException(questionType + " must have at least 2 options");
                }
                if (options.size() > 10) {
                    throw new InvalidDataException(questionType + " must not exceed 10 options");
                }
                if (correctCount != 1) {
                    throw new InvalidDataException(questionType + " must have exactly 1 correct answer");
                }
                if (questionType == QuestionType.TRUE_FALSE && options.size() != 2) {
                    throw new InvalidDataException("TRUE_FALSE must have exactly 2 options");
                }
                break;

            case MULTI_SELECT:
                if (options.size() < 2) {
                    throw new InvalidDataException("MULTI_SELECT must have at least 2 options");
                }
                if (correctCount < 1) {
                    throw new InvalidDataException("MULTI_SELECT must have at least 1 correct answer");
                }
                break;

            case SHORT_ANSWER:
            case ESSAY:
            case FILL_IN_BLANK:
                // These types can have 0-1 options (answer key)
                if (options.size() > 1) {
                    throw new InvalidDataException(questionType + " should have at most 1 option (answer key)");
                }
                break;

            default:
                throw new InvalidDataException("Unknown question type: " + questionType);
        }
    }

    private QuestionResponse toResponse(Question question) {
        List<AnswerOptionResponse> optionResponses = question.getAnswerOptions().stream()
                .map(option -> AnswerOptionResponse.builder()
                        .id(option.getId())
                        .questionId(question.getId())
                        .content(option.getContent())
                        .isCorrect(option.getIsCorrect())
                        .orderIndex(option.getOrderIndex())
                        .build())
                .toList();

        return QuestionResponse.builder()
                .id(question.getId())
                .questionBankId(question.getQuestionBank().getId())
                .content(question.getContent())
                .questionType(question.getQuestionType())
                .difficultyLevel(question.getDifficultyLevel())
                .explanation(question.getExplanation())
                .tags(question.getTags())
                .isAiGenerated(question.getIsAiGenerated())
                .sourceChunkId(question.getSourceChunkId())
                .answerOptions(optionResponses)
                .createdAt(question.getCreatedAt())
                .updatedAt(question.getUpdatedAt())
                .build();
    }
}



