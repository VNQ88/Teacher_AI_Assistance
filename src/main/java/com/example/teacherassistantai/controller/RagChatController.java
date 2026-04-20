package com.example.teacherassistantai.controller;

import com.example.teacherassistantai.common.response.ResponseData;
import com.example.teacherassistantai.dto.response.ChatMessageResponse;
import com.example.teacherassistantai.dto.request.SendChatMessageRequest;
import com.example.teacherassistantai.service.RagChatService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
@RequestMapping("/chat/sessions/{sessionId}")
@RequiredArgsConstructor
@Validated
@Tag(name = "RAG Chat Controller")
public class RagChatController {

    private final RagChatService ragChatService;

    @PostMapping("/messages")
    @PreAuthorize("hasAnyAuthority('STUDENT', 'TEACHER', 'ADMIN')")
    @Operation(
            summary = "Send message",
            description = "Send a user question and receive one assistant message using ChatMessageResponse contract. " +
                    "Response is wrapped in ResponseData with fields: status, message, data."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Assistant message generated",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "status": 200,
                                      "message": "Answer generated",
                                      "data": {
                                        "id": 125,
                                        "role": "ASSISTANT",
                                        "content": "De bai nay can ap dung dinh luat bao toan dong luong.",
                                        "agentType": "KNOWLEDGE_CHATBOT",
                                        "confidenceScore": 0.87,
                                        "confidenceLevel": "HIGH",
                                        "tokensUsed": 624,
                                        "responseTimeMs": 1420,
                                        "sources": ["Giao trinh Vat Ly 10 - Chuong 2", "Bai giang Dong luong"],
                                        "createdAt": "2026-04-12T14:32:10"
                                      }
                                    }
                                    """)))
    })
    public ResponseData<ChatMessageResponse> sendMessage(@PathVariable @Min(1) Long sessionId,
                                                         @RequestBody @Valid SendChatMessageRequest request) {
        ChatMessageResponse response = ragChatService.sendMessage(sessionId, request);
        return new ResponseData<>(HttpStatus.OK.value(), "Answer generated", response);
    }

    @GetMapping("/history")
    @PreAuthorize("hasAnyAuthority('STUDENT', 'TEACHER', 'ADMIN')")
    @Operation(
            summary = "Get chat history",
            description = "Get full chat history of a session sorted by createdAt ascending. " +
                    "Each item uses the same ChatMessageResponse contract as send-message."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Chat history returned",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "status": 200,
                                      "message": "Chat messages",
                                      "data": [
                                        {
                                          "id": 120,
                                          "role": "USER",
                                          "content": "Giai thich dinh luat Newton 2",
                                          "agentType": null,
                                          "confidenceScore": null,
                                          "confidenceLevel": null,
                                          "tokensUsed": null,
                                          "responseTimeMs": null,
                                          "sources": [],
                                          "createdAt": "2026-04-12T14:30:00"
                                        },
                                        {
                                          "id": 121,
                                          "role": "ASSISTANT",
                                          "content": "Dinh luat Newton 2 mo ta moi quan he giua luc va gia toc...",
                                          "agentType": "KNOWLEDGE_CHATBOT",
                                          "confidenceScore": null,
                                          "confidenceLevel": null,
                                          "tokensUsed": 510,
                                          "responseTimeMs": 1200,
                                          "sources": ["Giao trinh Vat Ly 10 - Chuong 1"],
                                          "createdAt": "2026-04-12T14:30:02"
                                        }
                                      ]
                                    }
                                    """)))
    })
    public ResponseData<List<ChatMessageResponse>> getHistory(@PathVariable @Min(1) Long sessionId) {
        return new ResponseData<>(HttpStatus.OK.value(), "Chat messages",
                ragChatService.getHistory(sessionId));
    }
}

