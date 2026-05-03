package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import com.example.teacherassistantai.common.file.InMemoryMultipartFile;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.integration.minio.MinioChannel;
import com.example.teacherassistantai.integration.minio.MinioProps;
import com.example.teacherassistantai.integration.tika.TikaMarkdownParser;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import io.minio.GetObjectResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
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
        uploadMarkdown(markdownObjectKey, document.getTitle(), cleanedMarkdown);

        document.setMarkdownObjectKey(markdownObjectKey);

//        // Keep skeleton flow explicit so status transitions are visible in DB.
//        document.setStatus(DocumentStatus.CHUNKING);
//        documentRepository.save(document);
//
//        documentChunkIngestionService.ingest(document, cleanedMarkdown);
//
//        document.setStatus(DocumentStatus.EMBEDDING);
//        documentRepository.save(document);

        document.setStatus(DocumentStatus.READY);
        documentRepository.save(document);
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

    private void uploadMarkdown(String objectKey, String title, String markdown) throws Exception {
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        String fileName = (title == null || title.isBlank() ? "document" : title.replaceAll("[^a-zA-Z0-9._-]", "_")) + ".md";

        InMemoryMultipartFile markdownFile = new InMemoryMultipartFile(
                "file",
                fileName,
                "text/markdown",
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
