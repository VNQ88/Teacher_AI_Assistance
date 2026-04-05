package com.example.teacherassistantai.service;

import com.example.teacherassistantai.dto.request.CreateAnswerOptionRequest;
import com.example.teacherassistantai.dto.request.UpdateAnswerOptionRequest;
import com.example.teacherassistantai.dto.response.AnswerOptionResponse;
import com.example.teacherassistantai.entity.AnswerOption;
import com.example.teacherassistantai.entity.Question;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.repository.AnswerOptionRepository;
import com.example.teacherassistantai.repository.QuestionRepository;
import com.example.teacherassistantai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnswerOptionService {

    private final AnswerOptionRepository answerOptionRepository;
    private final QuestionRepository questionRepository;
    private final UserRepository userRepository;

    @Transactional
    public AnswerOptionResponse createAnswerOption(Long questionId, CreateAnswerOptionRequest request) {
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Question not found with id: " + questionId));

        // Check permission
        validateOwnership(question);

        AnswerOption option = AnswerOption.builder()
                .question(question)
                .content(request.getContent().trim())
                .isCorrect(request.getIsCorrect())
                .orderIndex(request.getOrderIndex())
                .build();

        option = answerOptionRepository.save(option);
        log.info("Created answer option: id={}, questionId={}", option.getId(), questionId);

        return toResponse(option);
    }

    @Transactional
    public AnswerOptionResponse updateAnswerOption(Long id, UpdateAnswerOptionRequest request) {
        AnswerOption option = findAnswerOption(id);

        // Check permission
        validateOwnership(option.getQuestion());

        option.setContent(request.getContent().trim());
        option.setIsCorrect(request.getIsCorrect());
        option.setOrderIndex(request.getOrderIndex());

        option = answerOptionRepository.save(option);
        log.info("Updated answer option: id={}", option.getId());

        return toResponse(option);
    }

    @Transactional
    public void deleteAnswerOption(Long id) {
        AnswerOption option = findAnswerOption(id);

        // Check permission
        validateOwnership(option.getQuestion());

        answerOptionRepository.delete(option);
        log.info("Deleted answer option: id={}", id);
    }

    private AnswerOption findAnswerOption(Long id) {
        return answerOptionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Answer option not found with id: " + id));
    }

    private void validateOwnership(Question question) {
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        boolean isAdmin = currentUser.getRoles().stream()
                .anyMatch(role -> "ADMIN".equalsIgnoreCase(role.getName()));

        if (!isAdmin && !question.getQuestionBank().getCreatedBy().getId().equals(currentUser.getId())) {
            throw new InvalidDataException("You don't have permission to modify this answer option");
        }
    }

    private AnswerOptionResponse toResponse(AnswerOption option) {
        return AnswerOptionResponse.builder()
                .id(option.getId())
                .questionId(option.getQuestion().getId())
                .content(option.getContent())
                .isCorrect(option.getIsCorrect())
                .orderIndex(option.getOrderIndex())
                .build();
    }
}

