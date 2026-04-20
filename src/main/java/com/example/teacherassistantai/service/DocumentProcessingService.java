package com.example.teacherassistantai.service;

import com.example.teacherassistantai.common.enumerate.DocumentStatus;
import com.example.teacherassistantai.common.file.InMemoryMultipartFile;
import com.example.teacherassistantai.config.DocumentIngestionProps;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.exception.DocumentProcessingException;
import com.example.teacherassistantai.integration.docling.DoclingGateway;
import com.example.teacherassistantai.integration.docling.DoclingProps;
import com.example.teacherassistantai.integration.minio.MinioChannel;
import com.example.teacherassistantai.integration.minio.MinioProps;
import com.example.teacherassistantai.repository.DocumentChunkRepository;
import com.example.teacherassistantai.repository.DocumentRepository;
import io.minio.GetObjectResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    private final DoclingGateway doclingGateway;
    private final DoclingProps doclingProps;
    private final DocumentIngestionProps ingestionProps;
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

        // Keep skeleton flow explicit so status transitions are visible in DB.
        document.setStatus(DocumentStatus.CHUNKING);
        documentRepository.save(document);

        documentChunkIngestionService.ingest(document, cleanedMarkdown);

        document.setStatus(DocumentStatus.EMBEDDING);
        documentRepository.save(document);

        document.setStatus(DocumentStatus.READY);
        documentRepository.save(document);
    }

    private byte[] downloadOriginal(String objectKey) throws Exception {
        try (GetObjectResponse stream = minioChannel.downloadStream(minioProps.getBucket(), objectKey)) {
            return stream.readAllBytes();
        }
    }

    private String toMarkdown(Document document, byte[] sourceBytes) {
        boolean doOcr = doclingProps.getParse().isDoOcr();
        boolean includeImages = doclingProps.getParse().isIncludeImages();

        if ("PDF".equalsIgnoreCase(document.getFileType())) {
            List<byte[]> chunks = splitPdfByPageWindow(sourceBytes, ingestionProps.getPdfSplitPages());
            return parsePdfChunksSequential(chunks, doOcr, includeImages);
        }

        return doclingGateway.parseFile(
                sourceBytes,
                document.getTitle(),
                mapMimeType(document.getFileType()),
                doOcr,
                includeImages
        );
    }

    private List<byte[]> splitPdfByPageWindow(byte[] pdfBytes, int windowSize) {
        List<byte[]> chunks = new ArrayList<>();

        try (PDDocument sourceDoc = PDDocument.load(pdfBytes)) {
            int totalPages = sourceDoc.getNumberOfPages();
            int pageWindow = resolvePdfWindowSize(windowSize, totalPages);
            log.info("Split PDF: totalPages={}, pageWindow={}", totalPages, pageWindow);
            for (int start = 0; start < totalPages; start += pageWindow) {
                int endExclusive = Math.min(start + pageWindow, totalPages);
                try (PDDocument chunkDoc = new PDDocument();
                     ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                    for (int page = start; page < endExclusive; page++) {
                        chunkDoc.importPage(sourceDoc.getPage(page));
                    }
                    chunkDoc.save(outputStream);
                    chunks.add(outputStream.toByteArray());
                }
            }
        } catch (Exception ex) {
            throw new DocumentProcessingException("Failed to split PDF by page window", ex);
        }

        return chunks.isEmpty() ? List.of(pdfBytes) : chunks;
    }

    private String parsePdfChunksSequential(List<byte[]> chunks,
                                             boolean doOcr,
                                             boolean includeImages) {
         List<String> markdownParts = new ArrayList<>(chunks.size());
         long chunkDelayMillis = resolveChunkDelayMillis(chunks.size());
         try {
             for (int i = 0; i < chunks.size(); i++) {
                 String chunkName = "chunk-" + (i + 1) + ".pdf";
                 log.info("Parse PDF chunk {}/{} with Docling", i + 1, chunks.size());
                 String markdown = doclingGateway.parseFile(
                         chunks.get(i),
                         chunkName,
                         "application/pdf",
                         doOcr,
                         includeImages
                 );
                 markdownParts.add(markdown);

                 if (i < chunks.size() - 1 && chunkDelayMillis > 0) {
                     pauseBetweenChunks(chunkDelayMillis);
                 }
             }
             return String.join("\n\n", markdownParts);
         } catch (InterruptedException interruptedException) {
             Thread.currentThread().interrupt();
             throw new DocumentProcessingException("PDF chunk parsing interrupted", interruptedException);
         } catch (RuntimeException ex) {
             throw new DocumentProcessingException("Docling PDF chunk parse failed: " + ex.getMessage(), ex);
         }
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

    private int resolvePdfWindowSize(int defaultWindowSize, int totalPages) {
        int defaultWindow = Math.max(1, defaultWindowSize);
        if (totalPages >= ingestionProps.getPdfLargeThresholdPages()) {
            return Math.min(defaultWindow, Math.max(1, ingestionProps.getPdfLargeSplitPages()));
        }
        return defaultWindow;
    }

    private long resolveChunkDelayMillis(int chunkCount) {
        int largeChunkThreshold = Math.max(1,
                (int) Math.ceil((double) ingestionProps.getPdfLargeThresholdPages() / Math.max(1, ingestionProps.getPdfLargeSplitPages())));
        if (chunkCount >= largeChunkThreshold) {
            return Math.max(0L, ingestionProps.getPdfLargeChunkDelayMillis());
        }
        return Math.max(0L, ingestionProps.getPdfChunkDelayMillis());
    }

    private void pauseBetweenChunks(long delayMillis) throws InterruptedException {
        java.util.concurrent.TimeUnit.MILLISECONDS.sleep(delayMillis);
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
