package ai.docling.serve.client;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Test double for docling-serve-client. Loaded by tests so gateway reflection can be validated.
 */
public class DoclingServeClient {

    private final String baseUrl;

    public DoclingServeClient() {
        this("http://stub-docling:5001");
    }

    public DoclingServeClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static DoclingServeClient create(String baseUrl) {
        return new DoclingServeClient(baseUrl);
    }

    public Map<String, Object> parse(byte[] fileBytes,
                                     String fileName,
                                     boolean disableOcr,
                                     boolean disableImageProcessing) {
        String content = new String(fileBytes, StandardCharsets.UTF_8);
        String md = String.format(
                "FILE mode | baseUrl=%s | file=%s | ocr=%s | image=%s | bytes=%s",
                baseUrl,
                fileName,
                disableOcr,
                disableImageProcessing,
                content
        );
        return Map.of("markdown", md);
    }

    public Map<String, Object> parse(String sourceUrl,
                                     boolean disableOcr,
                                     boolean disableImageProcessing) {
        String md = String.format(
                "URL mode | baseUrl=%s | source=%s | ocr=%s | image=%s",
                baseUrl,
                sourceUrl,
                disableOcr,
                disableImageProcessing
        );
        return Map.of("markdown", md);
    }

    public static class Builder {
        private String baseUrl = "http://stub-docling:5001";

        public Builder url(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public DoclingServeClient build() {
            return new DoclingServeClient(baseUrl);
        }
    }
}

