package com.example.teacherassistantai.controller;

import com.example.teacherassistantai.common.response.ResponseData;
import com.example.teacherassistantai.dto.request.RagDebugRetrieveRequest;
import com.example.teacherassistantai.dto.response.RagDebugRetrieveResponse;
import com.example.teacherassistantai.entity.ChatSession;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.service.ChatSessionService;
import com.example.teacherassistantai.service.SubjectService;
import com.example.teacherassistantai.service.VectorRetrievalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/rag")
@RequiredArgsConstructor
@Validated
@Tag(name = "RAG Debug Controller")
public class RagDebugController {

    private final ChatSessionService chatSessionService;
    private final SubjectService subjectService;
    private final VectorRetrievalService vectorRetrievalService;

    @PostMapping("/debug-retrieve")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Debug RAG retrieval", description = "Return candidates, parent groups, selected chunks, scores, and prompt context preview")
    public ResponseData<RagDebugRetrieveResponse> debugRetrieve(@RequestBody @Valid RagDebugRetrieveRequest request) {
        ChatSession session = chatSessionService.getOwnedSession(request.getSessionId());
        if (session.getSubject() == null) {
            throw new InvalidDataException("Session has no associated subject");
        }
        subjectService.validateSubjectOwnershipById(session.getSubject().getId());
        RagDebugRetrieveResponse response = vectorRetrievalService.debugRetrieve(
                session,
                request.getQuestion(),
                request.getTopK()
        );
        return new ResponseData<>(HttpStatus.OK.value(), "RAG debug retrieve", response);
    }
}
