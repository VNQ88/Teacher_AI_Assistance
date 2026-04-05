package com.example.teacherassistantai.controller;

import com.example.teacherassistantai.common.response.PageResponse;
import com.example.teacherassistantai.common.response.ResponseData;
import com.example.teacherassistantai.dto.request.CreateChatSessionRequest;
import com.example.teacherassistantai.dto.response.ChatSessionResponse;
import com.example.teacherassistantai.service.ChatSessionService;
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

@Slf4j
@RestController
@RequestMapping("/chat/sessions")
@RequiredArgsConstructor
@Validated
@Tag(name = "Chat Session Controller")
public class ChatSessionController {

    private final ChatSessionService chatSessionService;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('STUDENT', 'TEACHER', 'ADMIN')")
    @Operation(summary = "Create chat session for a subject")
    public ResponseData<ChatSessionResponse> create(@RequestBody @Valid CreateChatSessionRequest request) {
        ChatSessionResponse response = chatSessionService.create(request);
        return new ResponseData<>(HttpStatus.CREATED.value(), "Chat session created", response);
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('STUDENT', 'TEACHER', 'ADMIN')")
    @Operation(summary = "Get my chat sessions")
    public ResponseData<PageResponse<?>> getMySessions(@RequestParam(required = false) Long subjectId,
                                                       @RequestParam(defaultValue = "0") int pageNo,
                                                       @RequestParam(defaultValue = "20") @Min(1) int pageSize) {
        return new ResponseData<>(HttpStatus.OK.value(), "Chat sessions",
                chatSessionService.getMySessions(subjectId, pageNo, pageSize));
    }

    @GetMapping("/{sessionId}")
    @PreAuthorize("hasAnyAuthority('STUDENT', 'TEACHER', 'ADMIN')")
    @Operation(summary = "Get chat session detail")
    public ResponseData<ChatSessionResponse> getById(@PathVariable @Min(1) Long sessionId) {
        return new ResponseData<>(HttpStatus.OK.value(), "Chat session",
                chatSessionService.getMySession(sessionId));
    }

    @PatchMapping("/{sessionId}/close")
    @PreAuthorize("hasAnyAuthority('STUDENT', 'TEACHER', 'ADMIN')")
    @Operation(summary = "Close chat session")
    public ResponseData<Void> close(@PathVariable @Min(1) Long sessionId) {
        chatSessionService.close(sessionId);
        return new ResponseData<>(HttpStatus.OK.value(), "Chat session closed");
    }
}

