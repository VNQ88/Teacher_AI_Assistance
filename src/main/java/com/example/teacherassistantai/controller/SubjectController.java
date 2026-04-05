package com.example.teacherassistantai.controller;

import com.example.teacherassistantai.common.response.ResponseData;
import com.example.teacherassistantai.dto.request.CreateSubjectRequest;
import com.example.teacherassistantai.dto.request.UpdateSubjectRequest;
import com.example.teacherassistantai.dto.response.SubjectResponse;
import com.example.teacherassistantai.service.SubjectService;
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

@Slf4j
@RestController
@RequestMapping("/subjects")
@RequiredArgsConstructor
@Validated
@Tag(name = "Subject Controller")
public class SubjectController {

    private final SubjectService subjectService;

    @GetMapping
    @Operation(summary = "Get all subjects")
    public ResponseData<List<SubjectResponse>> getAllSubjects() {
        return new ResponseData<>(HttpStatus.OK.value(), "subjects", subjectService.getAllSubjects());
    }

    @GetMapping("/{subjectId}")
    @Operation(summary = "Get subject detail")
    public ResponseData<SubjectResponse> getSubjectById(@PathVariable @Min(1) Long subjectId) {
        return new ResponseData<>(HttpStatus.OK.value(), "subject", subjectService.getSubjectById(subjectId));
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Create subject")
    public ResponseData<SubjectResponse> createSubject(@RequestBody @Valid CreateSubjectRequest request) {
        log.info("Create subject with code={}", request.getCode());
        return new ResponseData<>(HttpStatus.CREATED.value(), "Subject created", subjectService.createSubject(request));
    }

    @PutMapping("/{subjectId}")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Update subject")
    public ResponseData<SubjectResponse> updateSubject(@PathVariable @Min(1) Long subjectId,
                                                       @RequestBody @Valid UpdateSubjectRequest request) {
        return new ResponseData<>(HttpStatus.OK.value(), "Subject updated", subjectService.updateSubject(subjectId, request));
    }

    @DeleteMapping("/{subjectId}")
    @PreAuthorize("hasAuthority('ADMIN')")
    @Operation(summary = "Delete subject")
    public ResponseData<Void> deleteSubject(@PathVariable @Min(1) Long subjectId) {
        subjectService.deleteSubject(subjectId);
        return new ResponseData<>(HttpStatus.OK.value(), "Subject deleted");
    }
}

