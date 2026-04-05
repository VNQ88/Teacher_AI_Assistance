package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.response.PageResponse;
import com.example.teacherassistantai.dto.request.CreateQuestionBankRequest;
import com.example.teacherassistantai.dto.request.UpdateQuestionBankRequest;
import com.example.teacherassistantai.dto.response.QuestionBankResponse;
import com.example.teacherassistantai.entity.QuestionBank;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.repository.QuestionBankRepository;
import com.example.teacherassistantai.repository.SubjectRepository;
import com.example.teacherassistantai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuestionBankService {

    private final QuestionBankRepository questionBankRepository;
    private final SubjectRepository subjectRepository;
    private final UserRepository userRepository;

    @Transactional
    public QuestionBankResponse createQuestionBank(CreateQuestionBankRequest request) {
        Subject subject = subjectRepository.findById(request.getSubjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Subject not found with id: " + request.getSubjectId()));

        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        QuestionBank questionBank = QuestionBank.builder()
                .title(request.getTitle().trim())
                .description(request.getDescription())
                .subject(subject)
                .sourceDocument(null) // TODO: handle document later
                .createdBy(currentUser)
                .published(false)
                .build();

        questionBank = questionBankRepository.save(questionBank);
        log.info("Created question bank: id={}, title={}", questionBank.getId(), questionBank.getTitle());

        return toResponse(questionBank);
    }

    @Transactional(readOnly = true)
    public PageResponse<?> getAllQuestionBanks(Long subjectId, Boolean published, int pageNo, int pageSize) {
        Page<QuestionBank> page = questionBankRepository.findByFilters(
                subjectId, published, PageRequest.of(pageNo, pageSize)
        );

        List<QuestionBankResponse> responses = page.getContent().stream()
                .map(this::toResponse)
                .toList();

        return PageResponse.<List<QuestionBankResponse>>builder()
                .pageNo(pageNo)
                .pageSize(pageSize)
                .totalPage(page.getTotalPages())
                .items(responses)
                .build();
    }

    @Transactional(readOnly = true)
    public QuestionBankResponse getQuestionBankById(Long id) {
        QuestionBank questionBank = findQuestionBank(id);
        return toResponse(questionBank);
    }

    @Transactional
    public QuestionBankResponse updateQuestionBank(Long id, UpdateQuestionBankRequest request) {
        QuestionBank questionBank = findQuestionBank(id);

        // Check permission: only creator or admin can update
        validateOwnership(questionBank);

        questionBank.setTitle(request.getTitle().trim());
        questionBank.setDescription(request.getDescription());
        if (request.getPublished() != null) {
            questionBank.setPublished(request.getPublished());
        }

        questionBank = questionBankRepository.save(questionBank);
        log.info("Updated question bank: id={}", questionBank.getId());

        return toResponse(questionBank);
    }

    @Transactional
    public void deleteQuestionBank(Long id) {
        QuestionBank questionBank = findQuestionBank(id);

        // Check permission
        validateOwnership(questionBank);

        // Check if it's being used in any exam
        if (!questionBank.getQuestions().isEmpty()) {
            throw new InvalidDataException("Cannot delete question bank with existing questions. Delete questions first.");
        }

        questionBankRepository.delete(questionBank);
        log.info("Deleted question bank: id={}", id);
    }

    private QuestionBank findQuestionBank(Long id) {
        return questionBankRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Question bank not found with id: " + id));
    }

    private void validateOwnership(QuestionBank questionBank) {
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        boolean isAdmin = currentUser.getRoles().stream()
                .anyMatch(role -> "ADMIN".equalsIgnoreCase(role.getName()));

        if (!isAdmin && !questionBank.getCreatedBy().getId().equals(currentUser.getId())) {
            throw new InvalidDataException("You don't have permission to modify this question bank");
        }
    }

    private QuestionBankResponse toResponse(QuestionBank questionBank) {
        return QuestionBankResponse.builder()
                .id(questionBank.getId())
                .title(questionBank.getTitle())
                .description(questionBank.getDescription())
                .subjectId(questionBank.getSubject().getId())
                .subjectName(questionBank.getSubject().getName())
                .sourceDocumentId(questionBank.getSourceDocument() != null ? questionBank.getSourceDocument().getId() : null)
                .createdById(questionBank.getCreatedBy().getId())
                .createdByName(questionBank.getCreatedBy().getFullName())
                .published(questionBank.getPublished())
                .questionCount(questionBank.getQuestions().size())
                .createdAt(questionBank.getCreatedAt())
                .updatedAt(questionBank.getUpdatedAt())
                .build();
    }
}


