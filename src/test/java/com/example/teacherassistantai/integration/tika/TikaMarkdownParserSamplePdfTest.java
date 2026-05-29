package com.example.teacherassistantai.integration.tika;

import com.example.teacherassistantai.config.DocumentIngestionProps;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TikaMarkdownParserSamplePdfTest {

    private final TikaMarkdownParser parser = new TikaMarkdownParser(
            ingestionProps(),
            new PdfMarkdownPostProcessor()
    );

    @Test
    void parseSampleTextBasedPdfBooksToStructuredMarkdown() throws Exception {
        Path inputDir = Path.of("input");
        Assumptions.assumeTrue(Files.isDirectory(inputDir), "sample input directory is not available");

        ParsedSample partyHistory = parseSample(inputDir.resolve("gt-lich-su-dang-csvn-ban-tuyen-giao-tw.pdf"));
        assertThat(partyHistory.markdown()).contains("### Chương nhập môn");
        assertThat(partyHistory.markdown()).contains("### Chương 1");
        assertThat(partyHistory.markdown()).doesNotContain("\n2\n");
        assertThat(partyHistory.markdown()).doesNotContain("\n#### XX.");
        assertThat(partyHistory.markdown()).doesNotContain("\n##### 1.000");
        assertThat(partyHistory.markdown()).doesNotContain("\n##### 1.168");

        ParsedSample law = parseSample(inputDir.resolve("Bài giảng Pháp luật đại cương Th.S Lê Thị Bích Ngọc.pdf"));
        assertThat(law.markdown()).contains("### Chương 1");
        assertThat(law.markdown()).contains("#### 1.1.");
        assertThat(countOccurrences(law.markdown(), "Chương 1: Lý luận chung về nhà nước")).isLessThan(8);

        ParsedSample philosophy = parseSample(inputDir.resolve("75770b9b-cdbf-4038-90e2-f25e1f4426fe_triethocmaclenin.pdf"));
        assertThat(philosophy.markdown()).contains("## Phần I");
        assertThat(philosophy.markdown()).contains("### Chương I");
        assertThat(philosophy.markdown()).contains("#### I- Triết học là gì ?");
    }

    private ParsedSample parseSample(Path path) throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(path), "sample PDF is not available: " + path);
        String markdown = parser.parseToMarkdown(
                Files.readAllBytes(path),
                stripExtension(path.getFileName().toString()),
                "application/pdf"
        );
        assertThat(markdown).isNotBlank();
        assertThat(markdown).contains("# " + stripExtension(path.getFileName().toString()));

        Path output = Path.of("output", "tika-" + stripExtension(path.getFileName().toString()) + ".md");
        Files.createDirectories(output.getParent());
        Files.writeString(output, markdown);
        return new ParsedSample(markdown);
    }

    private static DocumentIngestionProps ingestionProps() {
        DocumentIngestionProps props = new DocumentIngestionProps();
        props.setTikaMaxChars(5_000_000);
        return props;
    }

    private static String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int start = 0;
        while (true) {
            int index = value.indexOf(needle, start);
            if (index < 0) {
                return count;
            }
            count++;
            start = index + needle.length();
        }
    }

    private record ParsedSample(String markdown) {
    }
}
