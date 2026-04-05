package com.example.teacherassistantai.service;

import com.example.teacherassistantai.config.DocumentIngestionProps;
import com.example.teacherassistantai.entity.Document;
import com.example.teacherassistantai.integration.docling.DoclingGateway;
import com.example.teacherassistantai.integration.docling.DoclingProps;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentProcessingServicePdfSplitTest {

    @Test
    void splitPdfByPageWindow_shouldSplitBy10Pages() throws Exception {
        DocumentProcessingService service = newService(new ChunkNameGateway(), 2);
        byte[] pdfBytes = create25PagePdfBytes();

        Method splitMethod = DocumentProcessingService.class
                .getDeclaredMethod("splitPdfByPageWindow", byte[].class, int.class);
        splitMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<byte[]> chunks = (List<byte[]>) splitMethod.invoke(service, pdfBytes, 10);

        assertEquals(3, chunks.size());
        assertEquals(10, countPages(chunks.get(0)));
        assertEquals(10, countPages(chunks.get(1)));
        assertEquals(5, countPages(chunks.get(2)));
    }

    @Test
    void toMarkdown_pdfShouldParseSequentialAndKeepChunkOrder() throws Exception {
        DocumentProcessingService service = newService(new DelayedChunkNameGateway(), 3);
        Document document = new Document();
        document.setFileType("PDF");
        document.setTitle("lecture.pdf");

        byte[] pdfBytes = create25PagePdfBytes();

        Method toMarkdownMethod = DocumentProcessingService.class
                .getDeclaredMethod("toMarkdown", Document.class, byte[].class);
        toMarkdownMethod.setAccessible(true);

        String markdown = (String) toMarkdownMethod.invoke(service, document, pdfBytes);

        assertEquals("chunk-1.pdf\n\nchunk-2.pdf\n\nchunk-3.pdf", markdown);
    }

    @Test
    void splitPdfByPageWindow_shouldSplitBy8PagesWhenMoreThan150Pages() throws Exception {
        DocumentProcessingService service = newService(new ChunkNameGateway(), 2);
        byte[] pdfBytes = createPdfBytes(151);

        Method splitMethod = DocumentProcessingService.class
                .getDeclaredMethod("splitPdfByPageWindow", byte[].class, int.class);
        splitMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<byte[]> chunks = (List<byte[]>) splitMethod.invoke(service, pdfBytes, 10);

        assertEquals(19, chunks.size());
        assertEquals(8, countPages(chunks.getFirst()));
        assertEquals(7, countPages(chunks.getLast()));
    }

    private DocumentProcessingService newService(DoclingGateway doclingGateway,
                                                 int parseConcurrency) {
        DocumentIngestionProps ingestionProps = new DocumentIngestionProps();
        ingestionProps.setParseConcurrency(parseConcurrency);
        ingestionProps.setPdfSplitPages(10);
        ingestionProps.setPdfLargeThresholdPages(150);
        ingestionProps.setPdfLargeSplitPages(8);

        DoclingProps doclingProps = new DoclingProps();
        doclingProps.getParse().setDoOcr(false);
        doclingProps.getParse().setIncludeImages(false);

        return new DocumentProcessingService(
                null,
                null,
                null,
                null,
                doclingGateway,
                doclingProps,
                ingestionProps,
                null
        );
    }

    private byte[] create25PagePdfBytes() throws Exception {
        return createPdfBytes(25);
    }

    private byte[] createPdfBytes(int pages) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            document.save(outputStream);
            return outputStream.toByteArray();
        }
    }

    private int countPages(byte[] pdfBytes) throws Exception {
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            return document.getNumberOfPages();
        }
    }

    private static class ChunkNameGateway implements DoclingGateway {
        @Override
        public String parseFile(byte[] fileBytes,
                                String fileName,
                                String mimeType,
                                boolean disableOcr,
                                boolean disableImageProcessing) {
            return fileName;
        }

        @Override
        public String parseUrl(String sourceUrl, boolean disableOcr, boolean disableImageProcessing) {
            throw new UnsupportedOperationException("Not used in this test");
        }
    }

    private static class DelayedChunkNameGateway extends ChunkNameGateway {
        @Override
        public String parseFile(byte[] fileBytes,
                                String fileName,
                                String mimeType,
                                boolean disableOcr,
                                boolean disableImageProcessing) {
            try {
                if (fileName.contains("chunk-1")) {
                    Thread.sleep(140);
                } else if (fileName.contains("chunk-2")) {
                    Thread.sleep(20);
                } else {
                    Thread.sleep(60);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return fileName;
        }
    }
}
