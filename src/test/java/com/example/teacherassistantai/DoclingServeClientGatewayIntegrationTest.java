package com.example.teacherassistantai;

import com.example.teacherassistantai.integration.docling.DoclingGateway;
import com.example.teacherassistantai.integration.docling.DoclingProps;
import com.example.teacherassistantai.integration.docling.DoclingServeClientGateway;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoclingServeClientGatewayIntegrationTest {

    private HttpServer server;
    private DoclingGateway gateway;
    private volatile boolean returnFileError;
    private volatile boolean returnFileValidationError;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());

        server.createContext("/v1/convert/file", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("name=\"files\""));
            assertTrue(body.contains("sample.pdf"));
            assertTrue(body.contains("do_ocr"));
            assertTrue(body.contains("include_images"));
            if (returnFileValidationError) {
                writeValidation422(exchange);
                return;
            }
            if (returnFileError) {
                writeServerError500(exchange);
                return;
            }
            writeJson(exchange, "{\"document\":{\"filename\":\"sample.pdf\",\"md_content\":\"FILE mode markdown\",\"text_content\":\"FILE mode text\"},\"status\":\"pending\",\"errors\":[],\"processing_time\":0}");
        });

        server.createContext("/v1/convert/url", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("source"));
            assertTrue(body.contains("https://minio.local/document.pdf"));
            assertTrue(body.contains("do_ocr"));
            assertTrue(body.contains("include_images"));
            writeJson(exchange, "{\"document\":{\"filename\":\"from-url.pdf\",\"md_content\":\"URL mode markdown\"},\"status\":\"pending\",\"errors\":[],\"processing_time\":0}");
        });

        server.start();

        DoclingProps props = new DoclingProps();
        props.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        props.setTimeoutSeconds(5);
        gateway = new DoclingServeClientGateway(props);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parseFile_shouldReturnMarkdownFromDoclingResponse() {
        String markdown = gateway.parseFile(
                "hello-docling".getBytes(StandardCharsets.UTF_8),
                "sample.pdf",
                "application/pdf",
                false,
                true
        );

        assertEquals("FILE mode markdown", markdown);
    }

    @Test
    void parseUrl_shouldReturnMarkdownFromDoclingResponse() {
        String markdown = gateway.parseUrl(
                "https://minio.local/document.pdf",
                true,
                false
        );

        assertEquals("URL mode markdown", markdown);
    }

    @Test
    void parseFile_shouldWrapHttp5xxWithExpectedErrorFormat() {
        returnFileError = true;

        RuntimeException ex = assertThrows(RuntimeException.class, () -> gateway.parseFile(
                "hello-docling".getBytes(StandardCharsets.UTF_8),
                "sample.pdf",
                "application/pdf",
                false,
                true
        ));

        assertTrue(ex.getMessage().contains("Docling parseFile failed: HTTP 500"));
        assertTrue(ex.getMessage().contains("docling-internal-error"));
    }

    @Test
    void parseFile_shouldWrapHttp422WithExpectedErrorFormat() {
        returnFileValidationError = true;

        RuntimeException ex = assertThrows(RuntimeException.class, () -> gateway.parseFile(
                "hello-docling".getBytes(StandardCharsets.UTF_8),
                "sample.pdf",
                "application/pdf",
                false,
                true
        ));

        assertTrue(ex.getMessage().contains("Docling parseFile failed: HTTP 422"));
        assertTrue(ex.getMessage().contains("\"loc\":[\"body\",\"files\"]"));
    }

    private void writeJson(HttpExchange exchange, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void writeServerError500(HttpExchange exchange) throws IOException {
        String body = "docling-internal-error";
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(500, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void writeValidation422(HttpExchange exchange) throws IOException {
        String body = "{\"detail\":[{\"type\":\"missing\",\"loc\":[\"body\",\"files\"],\"msg\":\"Field required\",\"input\":null}]}";
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(422, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
