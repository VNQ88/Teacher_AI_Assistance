package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import com.example.teacherassistantai.common.enumerate.DocumentEnrichmentStatus;
import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import com.example.teacherassistantai.common.file.InMemoryMultipartFile;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.integration.minio.MinioChannel;
import com.example.teacherassistantai.integration.minio.MinioProps;
import com.example.teacherassistantai.integration.tika.TikaMarkdownParser;
import com.example.teacherassistantai.repository.DocumentRepository;
import io.minio.GetObjectResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentProcessingService {

    private static final Pattern IMAGE_BASE64_PATTERN = Pattern.compile("(?im)^\\s*Image:base64,.*$");
    private static final Pattern DATA_URI_IMAGE_PATTERN = Pattern.compile("(?i)data:image/[^;]+;base64,[a-zA-Z0-9+/=]+", Pattern.MULTILINE);

    private final DocumentRepository documentRepository;
    private final MinioChannel minioChannel;
    private final MinioProps minioProps;
    private final TikaMarkdownParser tikaMarkdownParser;
    private final DocumentChunkIngestionService documentChunkIngestionService;
    private final DocumentHierarchyArtifactService documentHierarchyArtifactService;
    private final DocumentHierarchyArtifactValidationService documentHierarchyArtifactValidationService;
    private final DocumentHierarchyPersistenceService documentHierarchyPersistenceService;
    private final DocumentEnrichmentService documentEnrichmentService;

    @Async("documentProcessingExecutor")
    public void processDocumentAsync(Long documentId) {
        try {
            processDocument(documentId);
        } catch (Exception ex) {
            markFailed(documentId, ex);
        }
    }

    protected void processDocument(Long documentId) throws Exception {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found with id: " + documentId));

        document.setStatus(DocumentStatus.PARSING);
        document.setProcessingError(null);
        documentRepository.save(document);

        byte[] sourceBytes = downloadOriginal(document.getOriginalObjectKey());
        String markdown = toMarkdown(document, sourceBytes);
        String cleanedMarkdown = sanitizeMarkdown(markdown);

        String markdownObjectKey = buildMarkdownObjectKey(document.getSubject().getId());
        String hierarchyObjectKey = buildSiblingObjectKey(markdownObjectKey, ".hierarchy.json");
        String chunksObjectKey = buildSiblingObjectKey(markdownObjectKey, ".chunks.jsonl");
        document.setMarkdownObjectKey(markdownObjectKey);
        document.setHierarchyObjectKey(hierarchyObjectKey);
        document.setChunksObjectKey(chunksObjectKey);
        DocumentHierarchyArtifactService.Artifacts artifacts =
                documentHierarchyArtifactService.buildArtifacts(document, cleanedMarkdown);
        DocumentHierarchyArtifactValidationService.ValidationReport validationReport =
                documentHierarchyArtifactValidationService.validate(document, artifacts);
        log.info("Validated hierarchy artifacts: documentId={}, warnings={}",
                documentId, validationReport.warningCount());

        uploadArtifact(markdownObjectKey, document.getTitle(), "text/markdown", artifacts.normalizedMarkdown());
        uploadArtifact(hierarchyObjectKey, document.getTitle(), "application/json", artifacts.hierarchyJson());
        uploadArtifact(chunksObjectKey, document.getTitle(), "application/x-ndjson", artifacts.chunksJsonl());

        document.setStatus(DocumentStatus.CHUNKING);
        documentRepository.save(document);

        documentChunkIngestionService.deleteExistingChunks(documentId);
        DocumentHierarchyPersistenceService.HierarchyPersistenceResult persistenceResult =
                documentHierarchyPersistenceService.persist(document, artifacts.hierarchyDocument());
        log.info("Persisted document hierarchy nodes: documentId={}, nodeCount={}",
                documentId, persistenceResult.nodes().size());

        document.setStatus(DocumentStatus.EMBEDDING);
        documentRepository.save(document);

        var chunks = documentChunkIngestionService.ingest(
                document,
                artifacts.hierarchyDocument(),
                persistenceResult.nodeByKey()
        );
        log.info("Persisted document hierarchical chunks and embeddings: documentId={}, chunkCount={}",
                documentId, chunks.size());

        document.setStatus(DocumentStatus.READY);
        document.setEnrichmentStatus(DocumentEnrichmentStatus.QUEUED);
        document.setEnrichmentError(null);
        documentRepository.save(document);
        documentEnrichmentService.enqueueDocumentEnrichment(
                documentId,
                false,
                List.of(DocumentNodeArtifactType.SUMMARY)
        );
    }

    private byte[] downloadOriginal(String objectKey) throws Exception {
        try (GetObjectResponse stream = minioChannel.downloadStream(minioProps.getBucket(), objectKey)) {
            return stream.readAllBytes();
        }
    }

    private String toMarkdown(Document document, byte[] sourceBytes) {
        return tikaMarkdownParser.parseToMarkdown(
                sourceBytes,
                document.getTitle(),
                mapMimeType(document.getFileType())
        );
    }

    private String sanitizeMarkdown(String markdown) {
        String cleaned = IMAGE_BASE64_PATTERN.matcher(markdown == null ? "" : markdown).replaceAll("");
        cleaned = DATA_URI_IMAGE_PATTERN.matcher(cleaned).replaceAll("[removed-base64-image]");
        return cleaned.trim();
    }

    private String buildMarkdownObjectKey(Long subjectId) {
        return String.format("uploads/subjects/%d/md/%s.md", subjectId, UUID.randomUUID());
    }

    private String buildSiblingObjectKey(String markdownObjectKey, String suffix) {
        if (markdownObjectKey == null || !markdownObjectKey.endsWith(".md")) {
            return markdownObjectKey + suffix;
        }
        return markdownObjectKey.substring(0, markdownObjectKey.length() - 3) + suffix;
    }

    private void uploadArtifact(String objectKey, String title, String contentType, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String extension = switch (contentType) {
            case "application/json" -> ".hierarchy.json";
            case "application/x-ndjson" -> ".chunks.jsonl";
            default -> ".md";
        };
        String fileName = (title == null || title.isBlank() ? "document" : title.replaceAll("[^a-zA-Z0-9._-]", "_")) + extension;

        InMemoryMultipartFile markdownFile = new InMemoryMultipartFile(
                "file",
                fileName,
                contentType,
                bytes
        );
        minioChannel.upload(markdownFile, objectKey);
    }

    private String mapMimeType(String fileType) {
        return switch (fileType.toUpperCase()) {
            case "DOCX" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "TXT" -> "text/plain";
            case "PDF" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }

    protected void markFailed(Long documentId, Exception ex) {
        documentRepository.findById(documentId).ifPresent(document -> {
            document.setStatus(DocumentStatus.FAILED);
            document.setProcessingError(ex.getMessage());
            documentRepository.save(document);
        });
        log.error("Document processing failed: id={}", documentId, ex);
    }
}
