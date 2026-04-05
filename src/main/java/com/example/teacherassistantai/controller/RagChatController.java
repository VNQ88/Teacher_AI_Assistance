package com.example.teacherassistantai.controller;

import com.example.teacherassistantai.common.response.PageResponse;
import com.example.teacherassistantai.common.response.ResponseData;
import com.example.teacherassistantai.dto.request.SendChatMessageRequest;
import com.example.teacherassistantai.dto.response.RagAnswerResponse;
import com.example.teacherassistantai.service.RagChatService;
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
@RequestMapping("/chat/sessions/{sessionId}")
@RequiredArgsConstructor
@Validated
@Tag(name = "RAG Chat Controller")
public class RagChatController {

    private final RagChatService ragChatService;

    @PostMapping("/messages")
    @PreAuthorize("hasAnyAuthority('STUDENT', 'TEACHER', 'ADMIN')")
    @Operation(summary = "Send question and get RAG answer")
    public ResponseData<RagAnswerResponse> sendMessage(@PathVariable @Min(1) Long sessionId,
                                                       @RequestBody @Valid SendChatMessageRequest request) {
        RagAnswerResponse response = ragChatService.sendMessage(sessionId, request);
        return new ResponseData<>(HttpStatus.OK.value(), "Answer generated", response);
    }

    @GetMapping("/messages")
    @PreAuthorize("hasAnyAuthority('STUDENT', 'TEACHER', 'ADMIN')")
    @Operation(summary = "Get message history")
    public ResponseData<PageResponse<?>> getMessages(@PathVariable @Min(1) Long sessionId,
                                                     @RequestParam(defaultValue = "0") int pageNo,
                                                     @RequestParam(defaultValue = "20") @Min(1) int pageSize) {
        return new ResponseData<>(HttpStatus.OK.value(), "Chat messages",
                ragChatService.getMessages(sessionId, pageNo, pageSize));
    }
}

