package com.example.teacherassistantai.controller;

import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import com.example.teacherassistantai.common.response.PageResponse;
import com.example.teacherassistantai.common.response.ResponseData;
import com.example.teacherassistantai.dto.request.DocumentArtifactEmbeddingBackfillRequest;
import com.example.teacherassistantai.dto.request.DocumentEnrichmentRequest;
import com.example.teacherassistantai.dto.request.UpdateDocumentRequest;
import com.example.teacherassistantai.dto.response.DocumentArtifactEmbeddingBackfillResponse;
import com.example.teacherassistantai.dto.response.DocumentChunkDebugResponse;
import com.example.teacherassistantai.dto.response.DocumentEnrichmentJobResponse;
import com.example.teacherassistantai.dto.response.DocumentHierarchyDebugResponse;
import com.example.teacherassistantai.dto.response.DocumentNodeDebugResponse;
import com.example.teacherassistantai.dto.response.DocumentNodeArtifactResponse;
import com.example.teacherassistantai.dto.response.DocumentResponse;
import com.example.teacherassistantai.service.DocumentDebugService;
import com.example.teacherassistantai.service.DocumentEnrichmentAdminService;
import com.example.teacherassistantai.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final DocumentDebugService documentDebugService;
    private final DocumentEnrichmentAdminService documentEnrichmentAdminService;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Upload document", description = "Upload PDF/DOCX/TXT document and trigger async processing")
    public ResponseData<DocumentResponse> uploadDocument(@RequestPart("file") MultipartFile file,
                                                         @RequestParam("subjectId") @Min(value = 1, message = "Mã môn học phải lớn hơn hoặc bằng 1") Long subjectId,
                                                         @RequestParam(value = "title", required = false) String title,
                                                         @RequestParam(value = "description", required = false) String description) {
        log.info("Upload document: subjectId={}, originalName={}", subjectId, file.getOriginalFilename());
        DocumentResponse response = documentService.uploadDocument(file, subjectId, title, description);
        return new ResponseData<>(HttpStatus.CREATED.value(), "Document uploaded", response);
    }

    @GetMapping("/artifact-embeddings/stats")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Get artifact embedding coverage", description = "Return completed summary artifact embedding coverage, optionally scoped by subject")
    public ResponseData<DocumentArtifactEmbeddingBackfillResponse> getArtifactEmbeddingCoverage(
            @RequestParam(required = false) Long subjectId) {
        return new ResponseData<>(HttpStatus.OK.value(), "Artifact embedding coverage",
                documentEnrichmentAdminService.getArtifactEmbeddingCoverage(null, subjectId));
    }

    @PostMapping("/artifact-embeddings/backfill")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Queue artifact embedding backfill", description = "Queue summary artifact embedding backfill for all documents or one subject")
    public ResponseData<DocumentArtifactEmbeddingBackfillResponse> backfillArtifactEmbeddings(
            @RequestParam(required = false) Long subjectId,
            @RequestBody(required = false) @Valid DocumentArtifactEmbeddingBackfillRequest request) {
        log.info("Queue artifact embedding backfill: subjectId={}, request={}", subjectId, request);
        return new ResponseData<>(HttpStatus.ACCEPTED.value(), "Artifact embedding backfill queued",
                documentEnrichmentAdminService.queueArtifactEmbeddingBackfill(null, subjectId, request));
    }

    @GetMapping("/{documentId}")
    @Operation(summary = "Get document detail")
    public ResponseData<DocumentResponse> getDocumentById(@PathVariable @Min(value = 1, message = "Mã tài liệu phải lớn hơn hoặc bằng 1") Long documentId) {
        return new ResponseData<>(HttpStatus.OK.value(), "Document", documentService.getDocumentById(documentId));
    }

    @GetMapping
    @Operation(summary = "Get documents", description = "Get paginated documents by subject and/or status")
    public ResponseData<PageResponse<?>> getDocuments(@RequestParam(required = false) Long subjectId,
                                                      @RequestParam(required = false) DocumentStatus status,
                                                      @RequestParam(defaultValue = "0") int pageNo,
                                                      @RequestParam(defaultValue = "20") @Min(value = 1, message = "Kích thước trang phải lớn hơn hoặc bằng 1") int pageSize) {
        return new ResponseData<>(HttpStatus.OK.value(), "Documents",
                documentService.getDocuments(subjectId, status, pageNo, pageSize));
    }

    @PatchMapping("/{documentId}")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Update document metadata", description = "Update title and description of a document")
    public ResponseData<DocumentResponse> updateDocument(@PathVariable @Min(value = 1, message = "Mã tài liệu phải lớn hơn hoặc bằng 1") Long documentId,
                                                         @RequestBody @Valid UpdateDocumentRequest request) {
        log.info("Update document metadata: id={}", documentId);
        return new ResponseData<>(HttpStatus.OK.value(), "Document updated",
                documentService.updateDocument(documentId, request));
    }

    @PostMapping("/{documentId}/reprocess")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Reprocess document", description = "Reset a FAILED document to UPLOADED and re-trigger the full processing pipeline")
    public ResponseData<DocumentResponse> reprocessDocument(@PathVariable @Min(value = 1, message = "Mã tài liệu phải lớn hơn hoặc bằng 1") Long documentId) {
        log.info("Reprocess document request: id={}", documentId);
        return new ResponseData<>(HttpStatus.ACCEPTED.value(), "Document reprocessing started",
                documentService.reprocessDocument(documentId));
    }

    @DeleteMapping("/{documentId}")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Delete document", description = "Delete document, all related chunks, and storage objects")
    public ResponseData<Void> deleteDocument(@PathVariable @Min(value = 1, message = "Mã tài liệu phải lớn hơn hoặc bằng 1") Long documentId) {
        log.info("Delete document request: id={}", documentId);
        documentService.deleteDocumentById(documentId);
        return new ResponseData<>(HttpStatus.OK.value(), "Document deleted");
    }

    @GetMapping("/{documentId}/hierarchy")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Debug document hierarchy", description = "Return document hierarchy tree persisted in document_nodes")
    public ResponseData<DocumentHierarchyDebugResponse> getDocumentHierarchy(@PathVariable @Min(value = 1, message = "Mã tài liệu phải lớn hơn hoặc bằng 1") Long documentId) {
        return new ResponseData<>(HttpStatus.OK.value(), "Document hierarchy",
                documentDebugService.getHierarchy(documentId));
    }

    @GetMapping("/{documentId}/nodes")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Debug document nodes", description = "Return flat document_nodes ordered by source order")
    public ResponseData<java.util.List<DocumentNodeDebugResponse>> getDocumentNodes(@PathVariable @Min(value = 1, message = "Mã tài liệu phải lớn hơn hoặc bằng 1") Long documentId) {
        return new ResponseData<>(HttpStatus.OK.value(), "Document nodes",
                documentDebugService.getNodes(documentId));
    }

    @GetMapping("/{documentId}/chunks")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Debug document chunks", description = "Return document_chunks with hierarchy metadata")
    public ResponseData<java.util.List<DocumentChunkDebugResponse>> getDocumentChunks(@PathVariable @Min(value = 1, message = "Mã tài liệu phải lớn hơn hoặc bằng 1") Long documentId,
                                                                                     @RequestParam(required = false) String type) {
        return new ResponseData<>(HttpStatus.OK.value(), "Document chunks",
                documentDebugService.getChunks(documentId, type));
    }

    @GetMapping("/{documentId}/artifacts")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Get document enrichment artifacts", description = "Return summary/question artifacts for all hierarchy nodes in a document")
    public ResponseData<java.util.List<DocumentNodeArtifactResponse>> getDocumentArtifacts(@PathVariable @Min(value = 1, message = "Mã tài liệu phải lớn hơn hoặc bằng 1") Long documentId) {
        return new ResponseData<>(HttpStatus.OK.value(), "Document artifacts",
                documentEnrichmentAdminService.getDocumentArtifacts(documentId));
    }

    @GetMapping("/{documentId}/artifact-embeddings/stats")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Get document artifact embedding coverage", description = "Return completed summary artifact embedding coverage for one document")
    public ResponseData<DocumentArtifactEmbeddingBackfillResponse> getDocumentArtifactEmbeddingCoverage(
            @PathVariable @Min(value = 1, message = "Mã tài liệu phải lớn hơn hoặc bằng 1") Long documentId) {
        return new ResponseData<>(HttpStatus.OK.value(), "Document artifact embedding coverage",
                documentEnrichmentAdminService.getArtifactEmbeddingCoverage(documentId, null));
    }

    @PostMapping("/{documentId}/artifact-embeddings/backfill")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Queue document artifact embedding backfill", description = "Queue summary artifact embedding backfill for one document")
    public ResponseData<DocumentArtifactEmbeddingBackfillResponse> backfillDocumentArtifactEmbeddings(
            @PathVariable @Min(value = 1, message = "Mã tài liệu phải lớn hơn hoặc bằng 1") Long documentId,
            @RequestBody(required = false) @Valid DocumentArtifactEmbeddingBackfillRequest request) {
        log.info("Queue document artifact embedding backfill: documentId={}, request={}", documentId, request);
        return new ResponseData<>(HttpStatus.ACCEPTED.value(), "Document artifact embedding backfill queued",
                documentEnrichmentAdminService.queueArtifactEmbeddingBackfill(documentId, null, request));
    }

    @GetMapping("/{documentId}/nodes/{nodeId}/artifacts")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Get node enrichment artifacts", description = "Return summary/question artifacts for one hierarchy node")
    public ResponseData<java.util.List<DocumentNodeArtifactResponse>> getNodeArtifacts(@PathVariable @Min(value = 1, message = "Mã tài liệu phải lớn hơn hoặc bằng 1") Long documentId,
                                                                                      @PathVariable @Min(value = 1, message = "Mã mục nội dung phải lớn hơn hoặc bằng 1") Long nodeId) {
        return new ResponseData<>(HttpStatus.OK.value(), "Document node artifacts",
                documentEnrichmentAdminService.getNodeArtifacts(documentId, nodeId));
    }

    @PostMapping("/{documentId}/enrich")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Queue document enrichment", description = "Queue summary/question artifact generation for all enrichable hierarchy nodes")
    public ResponseData<DocumentEnrichmentJobResponse> enrichDocument(@PathVariable @Min(value = 1, message = "Mã tài liệu phải lớn hơn hoặc bằng 1") Long documentId,
                                                                      @RequestBody(required = false) DocumentEnrichmentRequest request) {
        log.info("Queue document enrichment: documentId={}, request={}", documentId, request);
        return new ResponseData<>(HttpStatus.ACCEPTED.value(), "Document enrichment queued",
                documentEnrichmentAdminService.enrichDocument(documentId, request));
    }

    @PostMapping("/{documentId}/nodes/{nodeId}/enrich")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Queue node enrichment", description = "Queue summary/question artifact generation for one hierarchy node")
    public ResponseData<DocumentEnrichmentJobResponse> enrichNode(@PathVariable @Min(value = 1, message = "Mã tài liệu phải lớn hơn hoặc bằng 1") Long documentId,
                                                                  @PathVariable @Min(value = 1, message = "Mã mục nội dung phải lớn hơn hoặc bằng 1") Long nodeId,
                                                                  @RequestBody(required = false) DocumentEnrichmentRequest request) {
        log.info("Queue node enrichment: documentId={}, nodeId={}, request={}", documentId, nodeId, request);
        return new ResponseData<>(HttpStatus.ACCEPTED.value(), "Document node enrichment queued",
                documentEnrichmentAdminService.enrichNode(documentId, nodeId, request));
    }

    @PostMapping("/{documentId}/artifacts/retry-failed")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Retry failed enrichment artifacts", description = "Queue regeneration for failed or missing artifacts while completed artifacts are skipped")
    public ResponseData<DocumentEnrichmentJobResponse> retryFailedArtifacts(@PathVariable @Min(value = 1, message = "Mã tài liệu phải lớn hơn hoặc bằng 1") Long documentId,
                                                                           @RequestBody(required = false) DocumentEnrichmentRequest request) {
        log.info("Retry failed document artifacts: documentId={}, request={}", documentId, request);
        return new ResponseData<>(HttpStatus.ACCEPTED.value(), "Failed artifact retry queued",
                documentEnrichmentAdminService.retryFailedArtifacts(documentId, request));
    }

    @DeleteMapping("/{documentId}/artifacts")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Delete enrichment artifacts", description = "Delete all or selected artifact types for a document")
    public ResponseData<Void> deleteArtifacts(@PathVariable @Min(value = 1, message = "Mã tài liệu phải lớn hơn hoặc bằng 1") Long documentId,
                                              @RequestBody(required = false) DocumentEnrichmentRequest request) {
        log.info("Delete document artifacts: documentId={}, request={}", documentId, request);
        documentEnrichmentAdminService.deleteArtifacts(documentId, request);
        return new ResponseData<>(HttpStatus.OK.value(), "Document artifacts deleted");
    }
}
