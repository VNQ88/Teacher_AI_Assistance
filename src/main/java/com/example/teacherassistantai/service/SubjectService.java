package com.example.teacherassistantai.service;

import com.example.teacherassistantai.dto.request.CreateSubjectRequest;
import com.example.teacherassistantai.dto.request.UpdateSubjectRequest;
import com.example.teacherassistantai.dto.response.SubjectResponse;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.repository.SubjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SubjectService {

    private final SubjectRepository subjectRepository;

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

        Subject subject = Subject.builder()
                .name(request.getName().trim())
                .code(request.getCode().trim().toUpperCase())
                .description(request.getDescription())
                .subjectType(request.getSubjectType())
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
        subject.setSubjectType(request.getSubjectType());
        subject.setActive(request.getActive());

        return toResponse(subjectRepository.save(subject));
    }

    @Transactional
    public void deleteSubject(Long subjectId) {
        if (!subjectRepository.existsById(subjectId)) {
            throw new ResourceNotFoundException("Subject not found with id: " + subjectId);
        }
        subjectRepository.deleteById(subjectId);
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

