package com.example.teacherassistantai.integration.tika;

import com.example.teacherassistantai.config.DocumentIngestionProps;
import com.example.teacherassistantai.exception.DocumentProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;

import java.io.ByteArrayInputStream;
import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class TikaMarkdownParser {

    private final DocumentIngestionProps ingestionProps;
    private final PdfMarkdownPostProcessor pdfMarkdownPostProcessor;

    public String parseToMarkdown(byte[] sourceBytes, String fileName, String mimeType) {
        if (sourceBytes == null || sourceBytes.length == 0) {
            throw new DocumentProcessingException("Document source is empty");
        }

        String extractedText = isPdf(fileName, mimeType)
                ? extractPdfTextByPage(sourceBytes, fileName)
                : extractText(sourceBytes, fileName, mimeType);
        return pdfMarkdownPostProcessor.toMarkdown(extractedText, fileName);
    }

    private boolean isPdf(String fileName, String mimeType) {
        return "application/pdf".equalsIgnoreCase(mimeType)
                || (fileName != null && fileName.toLowerCase().endsWith(".pdf"));
    }

    private String extractPdfTextByPage(byte[] sourceBytes, String fileName) {
        try (PDDocument document = Loader.loadPDF(sourceBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            StringBuilder builder = new StringBuilder();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                if (page > 1) {
                    builder.append('\f');
                }
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                builder.append(stripper.getText(document));
            }
            return builder.toString();
        } catch (IOException | RuntimeException ex) {
            log.warn("PDFBox page-aware extraction failed for fileName={}, falling back to Tika", fileName, ex);
            return extractText(sourceBytes, fileName, "application/pdf");
        }
    }

    private String extractText(byte[] sourceBytes, String fileName, String mimeType) {
        AutoDetectParser parser = new AutoDetectParser();
        Metadata metadata = new Metadata();
        if (fileName != null && !fileName.isBlank()) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        }
        if (mimeType != null && !mimeType.isBlank()) {
            metadata.set(Metadata.CONTENT_TYPE, mimeType);
        }

        ParseContext context = new ParseContext();
        context.set(Parser.class, parser);
        context.set(PDFParserConfig.class, pdfParserConfig());

        int writeLimit = Math.max(1, ingestionProps.getTikaMaxChars());
        ContentHandler handler = new BodyContentHandler(writeLimit);
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(sourceBytes)) {
            parser.parse(inputStream, handler, metadata, context);
            return handler.toString();
        } catch (WriteLimitReachedException ex) {
            log.warn("Tika write limit reached for fileName={}, limit={}", fileName, writeLimit);
            return handler.toString();
        } catch (Exception ex) {
            throw new DocumentProcessingException("Tika document parse failed: " + ex.getMessage(), ex);
        }
    }

    private PDFParserConfig pdfParserConfig() {
        PDFParserConfig config = new PDFParserConfig();
        config.setSortByPosition(true);
        config.setEnableAutoSpace(true);
        config.setSuppressDuplicateOverlappingText(true);
        config.setExtractInlineImages(false);
        config.setExtractUniqueInlineImagesOnly(false);
        config.setExtractBookmarksText(true);
        return config;
    }
}
