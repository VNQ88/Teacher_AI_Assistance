package com.example.teacherassistantai.controller;

import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import com.example.teacherassistantai.common.response.PageResponse;
import com.example.teacherassistantai.common.response.ResponseData;
import com.example.teacherassistantai.dto.response.DocumentResponse;
import com.example.teacherassistantai.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
@Validated
@Tag(name = "Document Controller")
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Upload document", description = "Upload PDF/DOCX/TXT document and trigger async processing")
    public ResponseData<DocumentResponse> uploadDocument(@RequestPart("file") MultipartFile file,
                                                         @RequestParam("subjectId") @Min(1) Long subjectId,
                                                         @RequestParam(value = "classroomId", required = false) @Min(1) Long classroomId,
                                                         @RequestParam(value = "title", required = false) String title,
                                                         @RequestParam(value = "description", required = false) String description) {
        log.info("Upload document: subjectId={}, classroomId={}, originalName={}", subjectId, classroomId, file.getOriginalFilename());
        DocumentResponse response = documentService.uploadDocument(file, subjectId, classroomId, title, description);
        return new ResponseData<>(HttpStatus.CREATED.value(), "Document uploaded", response);
    }

    @GetMapping("/{documentId}")
    @Operation(summary = "Get document detail")
    public ResponseData<DocumentResponse> getDocumentById(@PathVariable @Min(1) Long documentId) {
        return new ResponseData<>(HttpStatus.OK.value(), "Document", documentService.getDocumentById(documentId));
    }

    @GetMapping
    @Operation(summary = "Get documents", description = "Get paginated documents by subject and/or status")
    public ResponseData<PageResponse<?>> getDocuments(@RequestParam(required = false) Long subjectId,
                                                      @RequestParam(required = false) DocumentStatus status,
                                                      @RequestParam(defaultValue = "0") int pageNo,
                                                      @RequestParam(defaultValue = "20") @Min(1) int pageSize) {
        return new ResponseData<>(HttpStatus.OK.value(), "Documents",
                documentService.getDocuments(subjectId, status, pageNo, pageSize));
    }
}

