package com.example.teacherassistantai.integration.docling;

import com.example.teacherassistantai.exception.ExternalServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class DoclingServeClientGateway implements DoclingGateway {

    private final DoclingProps doclingProps;
    private volatile RestClient restClient;
    private volatile Semaphore requestBulkhead;

    @Override
    public String parseFile(byte[] fileBytes,
                            String fileName,
                            String mimeType,
                            boolean doOcr,
                            boolean includeImages) {
        String endpoint = resolveEndpoint("/convert/file");
        Object response = executeWithRetry("parseFile", () -> getClient().post()
                .uri(endpoint)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(buildFileRequest(fileBytes, fileName, mimeType, doOcr, includeImages))
                .retrieve()
                .body(Object.class));
        return extractMarkdown(response);
    }

    @Override
    public String parseUrl(String sourceUrl,
                           boolean doOcr,
                           boolean includeImages) {
        String endpoint = resolveEndpoint("/convert/url");
        Map<String, Object> payload = Map.of(
                "source", sourceUrl,
                "do_ocr", doOcr,
                "include_images", includeImages
        );

        Object response = executeWithRetry("parseUrl", () -> getClient().post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(Object.class));
        return extractMarkdown(response);
    }

    private RestClient getClient() {
        RestClient cached = restClient;
        if (cached != null) return cached;
        synchronized (this) {
            if (restClient == null) {
                restClient = buildClient();
            }
            return restClient;
        }
    }

    private RestClient buildClient() {
        int timeout = Math.max(1, doclingProps.getTimeoutSeconds());
        HttpClient httpClient = HttpClient.newBuilder()
                // Uvicorn/FastAPI behind docling-serve is reliably HTTP/1.1 for multipart uploads.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(timeout))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(timeout));

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    private MultiValueMap<String, Object> buildFileRequest(byte[] fileBytes,
                                                           String fileName,
                                                           String mimeType,
                                                           boolean doOcr,
                                                           boolean includeImages) {
        String safeName = (fileName == null || fileName.isBlank()) ? "document.bin" : fileName;
        String contentType = (mimeType == null || mimeType.isBlank()) ? MediaType.APPLICATION_OCTET_STREAM_VALUE : mimeType;

        ByteArrayResource resource = new ByteArrayResource(fileBytes == null ? new byte[0] : fileBytes) {
            @Override
            public String getFilename() {
                return safeName;
            }
        };

        HttpHeaders filePartHeaders = new HttpHeaders();
        filePartHeaders.setContentType(MediaType.parseMediaType(contentType));

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("files", new HttpEntity<>(resource, filePartHeaders));
        body.add("do_ocr", String.valueOf(doOcr));
        body.add("include_images", String.valueOf(includeImages));
        return body;
    }

    private String resolveEndpoint(String path) {
        String configured = doclingProps.getBaseUrl();
        String base = (configured == null || configured.isBlank()) ? "http://localhost:5001" : configured;
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (normalized.endsWith("/v1")) {
            return normalized + path;
        }
        return normalized + "/v1" + path;
    }

    private String extractMarkdown(Object response) {
        switch (response) {
            case null -> throw new IllegalStateException("Docling response is null");
            case String s -> {
                if (isStatusOnlyToken(s)) {
                    throw new IllegalStateException("Docling response only contains status token: " + s);
                }
                return s;
            }
            case Map<?, ?> map -> {
                String fromDoclingDocument = extractFromDoclingDocumentEnvelope(map);
                if (fromDoclingDocument != null) {
                    return fromDoclingDocument;
                }

                // Backward-compatible extraction for older gateway mock payloads.
                for (String key : List.of("markdown", "md", "text", "content", "result")) {
                    Object value = map.get(key);
                    if (value != null && !String.valueOf(value).isBlank() && !isStatusOnlyToken(String.valueOf(value))) {
                        return String.valueOf(value);
                    }
                }

                for (Object value : map.values()) {
                    if (value instanceof String token && isStatusOnlyToken(token)) {
                        continue;
                    }
                    String nested = tryExtractFromObject(value);
                    if (nested != null) return nested;
                }
                throw new IllegalStateException("Docling response missing markdown content. keys=" + map.keySet());
            }
            default -> {
            }
        }
        if (response instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof String token && isStatusOnlyToken(token)) {
                    continue;
                }
                String nested = tryExtractFromObject(item);
                if (nested != null) return nested;
            }
        }

        String direct = tryExtractFromObject(response);
        if (direct != null) return direct;

        return response.toString();
    }

    private String tryExtractFromObject(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String s && !s.isBlank()) {
            return isStatusOnlyToken(s) ? null : s;
        }

        List<String> accessors = List.of(
                "markdown", "getMarkdown",
                "md", "getMd",
                "mdContent", "getMdContent",
                "text", "getText",
                "textContent", "getTextContent",
                "htmlContent", "getHtmlContent",
                "doctagsContent", "getDoctagsContent",
                "content", "getContent",
                "result", "getResult",
                "document", "getDocument",
                "output", "getOutput"
        );

        for (String accessor : accessors) {
            try {
                var m = obj.getClass().getMethod(accessor);
                if (m.getParameterCount() != 0) continue;
                Object value = m.invoke(obj);
                if (value == null) continue;
                if (value instanceof String s && !s.isBlank()) {
                    if (!isStatusOnlyToken(s)) {
                        return s;
                    }
                    continue;
                }
                String nested = tryExtractFromObject(value);
                if (nested != null) return nested;
            } catch (Exception ignored) {
                // try next accessor
            }
        }
        return null;
    }

    private boolean isStatusOnlyToken(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return normalized.equals("success")
                || normalized.equals("ok")
                || normalized.equals("done")
                || normalized.equals("true")
                || normalized.equals("false");
    }

    private Object executeWithRetry(String operation, Supplier<Object> supplier) {
        int attempts = Math.max(1, doclingProps.getRetryAttempts());
        long baseBackoff = Math.max(50L, doclingProps.getRetryBackoffMillis());

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return runWithBulkhead(supplier);
            } catch (RestClientResponseException ex) {
                if (isRetryableStatus(ex) && attempt < attempts) {
                    resetClient();
                    backoff(baseBackoff, attempt, operation, ex.getStatusCode().value());
                    continue;
                }
                throw new ExternalServiceException("Docling " + operation + " failed: HTTP " + ex.getStatusCode().value()
                        + ", body=" + ex.getResponseBodyAsString(), ex);
            } catch (ResourceAccessException ex) {
                if (attempt < attempts) {
                    resetClient();
                    backoff(baseBackoff, attempt, operation, null);
                    continue;
                }
                throw new ExternalServiceException("Docling " + operation + " failed: " + formatIoError(ex), ex);
            } catch (RuntimeException ex) {
                if (attempt < attempts && isTransientIo(ex)) {
                    resetClient();
                    backoff(baseBackoff, attempt, operation, null);
                    continue;
                }
                throw new ExternalServiceException("Docling " + operation + " failed: " + ex.getMessage(), ex);
            }
        }

        throw new IllegalStateException("Unexpected retry loop termination for Docling " + operation);
    }

    private boolean isRetryableStatus(RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        return status == 429 || status >= 500;
    }

    private boolean isTransientIo(Throwable ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        return message.contains("header parser received no bytes")
                || message.contains("i/o error")
                || message.contains("connection reset")
                || message.contains("connection refused")
                || message.contains("broken pipe")
                || message.contains("timed out")
                || message.contains("eof")
                || ex instanceof ResourceAccessException;
    }

    private String formatIoError(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String rootMsg = root.getMessage();
        return root.getClass().getSimpleName() + (rootMsg == null ? "" : ": " + rootMsg);
    }

    private void resetClient() {
        synchronized (this) {
            restClient = buildClient();
        }
    }

    private void backoff(long baseBackoffMillis, int attempt, String operation, Integer statusCode) {
        long base = Math.min(5_000L, baseBackoffMillis * (1L << Math.min(6, attempt - 1)));
        long jitter = ThreadLocalRandom.current().nextLong(50L, 251L);
        long sleepMillis = Math.min(8_000L, base + jitter);
        if (statusCode != null) {
            log.warn("Retry Docling {} after HTTP {} (attempt {}), backoff={}ms", operation, statusCode, attempt, sleepMillis);
        } else {
            log.warn("Retry Docling {} after transient I/O error (attempt {}), backoff={}ms", operation, attempt, sleepMillis);
        }
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new ExternalServiceException("Docling retry interrupted", interruptedException);
        }
    }

    private String extractFromDoclingDocumentEnvelope(Map<?, ?> map) {
        Object documentNode = map.get("document");
        if (documentNode == null) return null;

        if (documentNode instanceof Map<?, ?> documentMap) {
            for (String key : List.of("md_content", "text_content", "html_content", "doctags_content")) {
                Object value = documentMap.get(key);
                if (value != null) {
                    String text = String.valueOf(value).trim();
                    if (!text.isBlank() && !isStatusOnlyToken(text)) {
                        return text;
                    }
                }
            }
            return null;
        }

        // POJO fallback in case JSON is mapped to typed classes.
        return tryExtractFromObject(documentNode);
    }

    private Object runWithBulkhead(Supplier<Object> supplier) {
        Semaphore semaphore = getRequestBulkhead();
        boolean acquired = false;
        try {
            acquired = semaphore.tryAcquire(doclingProps.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!acquired) {
                throw new ExternalServiceException("Timeout waiting Docling request permit");
            }
            return supplier.get();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new ExternalServiceException("Interrupted while waiting Docling request permit", interruptedException);
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    private Semaphore getRequestBulkhead() {
        Semaphore cached = requestBulkhead;
        if (cached != null) return cached;
        synchronized (this) {
            if (requestBulkhead == null) {
                requestBulkhead = new Semaphore(Math.max(1, doclingProps.getMaxInflightRequests()));
            }
            return requestBulkhead;
        }
    }
}
