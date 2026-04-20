package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.SubjectType;
import com.example.teacherassistantai.dto.request.CreateSubjectRequest;
import com.example.teacherassistantai.dto.request.UpdateSubjectRequest;
import com.example.teacherassistantai.dto.response.SubjectResponse;
import com.example.teacherassistantai.entity.Role;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.repository.ExamQuestionRepository;
import com.example.teacherassistantai.repository.QuestionBankRepository;
import com.example.teacherassistantai.repository.QuestionRepository;
import com.example.teacherassistantai.repository.StudentAnswerRepository;
import com.example.teacherassistantai.repository.SubjectRepository;
import com.example.teacherassistantai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SubjectService {

    private final SubjectRepository subjectRepository;
    private final QuestionBankRepository questionBankRepository;
    private final QuestionRepository questionRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final StudentAnswerRepository studentAnswerRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<SubjectResponse> getAllSubjects() {
        return subjectRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public SubjectResponse getSubjectById(Long subjectId) {
        Subject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new ResourceNotFoundException("Subject not found with id: " + subjectId));
        return toResponse(subject);
    }

    @Transactional
    public SubjectResponse createSubject(CreateSubjectRequest request) {
        if (subjectRepository.existsByNameIgnoreCase(request.getName())) {
            throw new InvalidDataException("Subject name already exists");
        }
        if (subjectRepository.existsByCodeIgnoreCase(request.getCode())) {
            throw new InvalidDataException("Subject code already exists");
        }
        SubjectType subjectType = request.getSubjectType();
        if  (subjectType == null)
            subjectType = SubjectType.TEXT_BASED;

        User currentUser = getCurrentUser();
        Subject subject = Subject.builder()
                .ownerId(currentUser.getId())
                .name(request.getName().trim())
                .code(request.getCode().trim().toUpperCase())
                .description(request.getDescription())
                .subjectType(subjectType)
                .active(true)
                .build();

        return toResponse(subjectRepository.save(subject));
    }

    @Transactional
    public SubjectResponse updateSubject(Long subjectId, UpdateSubjectRequest request) {
        Subject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new ResourceNotFoundException("Subject not found with id: " + subjectId));

        subject.setName(request.getName().trim());
        subject.setDescription(request.getDescription());
        subject.setActive(request.getActive());

        SubjectType subjectType = request.getSubjectType();
        if  (subjectType == null)
            subjectType = SubjectType.TEXT_BASED;
        subject.setSubjectType(subjectType);
        return toResponse(subjectRepository.save(subject));
    }

    @Transactional
    public void deleteSubject(Long subjectId) {
        Subject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new ResourceNotFoundException("Subject not found with id: " + subjectId));

        User currentUser = getCurrentUser();
        boolean isAdmin = currentUser.getRoles().stream()
                .map(Role::getName)
                .anyMatch("ADMIN"::equalsIgnoreCase);

        Long ownerId = subject.getOwnerId();
        boolean isOwner = ownerId != null && ownerId.equals(currentUser.getId());

        if (!isAdmin && !isOwner) {
            throw new InvalidDataException("You don't have permission to delete this subject");
        }

        var questionBanks = questionBankRepository.findBySubject_Id(subjectId);
        if (!questionBanks.isEmpty()) {
            for (var questionBank : questionBanks) {
                List<Long> questionIds = questionRepository.findIdsByQuestionBankId(questionBank.getId());
                if (!questionIds.isEmpty()) {
                    List<Long> examQuestionIds = examQuestionRepository.findIdsByQuestionIdIn(questionIds);
                    if (!examQuestionIds.isEmpty()) {
                        studentAnswerRepository.deleteByExamQuestionIdIn(examQuestionIds);
                    }
                    examQuestionRepository.deleteByQuestionIdIn(questionIds);
                }
                questionRepository.deleteByQuestionBankId(questionBank.getId());
            }
            questionBankRepository.deleteAll(questionBanks);
        }

        subjectRepository.delete(subject);
    }

    private User getCurrentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ResourceNotFoundException("User not authenticated");
        }
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private SubjectResponse toResponse(Subject subject) {
        return SubjectResponse.builder()
                .id(subject.getId())
                .name(subject.getName())
                .code(subject.getCode())
                .description(subject.getDescription())
                .subjectType(subject.getSubjectType())
                .active(subject.getActive())
                .build();
    }
}

