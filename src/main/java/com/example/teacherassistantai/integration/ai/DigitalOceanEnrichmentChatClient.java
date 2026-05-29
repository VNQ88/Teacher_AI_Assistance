package com.example.teacherassistantai.integration.ai;

import com.example.teacherassistantai.config.RagProperties;
import com.example.teacherassistantai.exception.BackgroundTransientAiException;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class DigitalOceanEnrichmentChatClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RagProperties ragProperties;
    private final DigitalOceanTokenRateLimitHeaderParser headerParser;

    public DigitalOceanEnrichmentChatClient(RagProperties ragProperties,
                                            DigitalOceanTokenRateLimitHeaderParser headerParser) {
        this.ragProperties = ragProperties;
        this.headerParser = headerParser;
    }

    public AiChatCompletion complete(String prompt, Double temperature, AiModelRoute route) {
        if (!StringUtils.hasText(route.apiKey())) {
            throw new InvalidDataException("API key is not configured for route: accountAlias=" + route.accountAlias());
        }
        RagProperties.Ai.EnrichmentAi enrichment = ragProperties.getAi().getEnrichment();
        int timeoutSeconds = AiModelRoutingService.ACCOUNT_RAG.equals(route.accountAlias())
                ? enrichment.getOnDemandTimeoutSeconds()
                : enrichment.getTimeoutSeconds();
        RestClient restClient = restClient(route.baseUrl(), route.apiKey(), timeoutSeconds);
        Map<String, Object> body = Map.of(
                "model", route.model(),
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "temperature", temperature == null ? 0.2 : temperature
        );

        try {
            ResponseEntity<String> response = restClient.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(String.class);
            AiRateLimitSnapshot snapshot = headerParser.parse(response.getHeaders());
            JsonNode responseBody = parseBody(response.getBody());
            return new AiChatCompletion(
                    content(responseBody),
                    usage(responseBody),
                    snapshot,
                    route.model(),
                    route.workload()
            );
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 429) {
                throw new DigitalOceanEnrichmentRateLimitException(headerParser.parse(ex.getResponseHeaders()), ex);
            }
            if (isTransientStatus(ex.getStatusCode().value())) {
                throw new BackgroundTransientAiException(
                        "TRANSIENT_AI_ERROR",
                        "DigitalOcean enrichment transient HTTP " + ex.getStatusCode().value(),
                        ex
                );
            }
            throw ex;
        } catch (RestClientException ex) {
            if (isTimeout(ex)) {
                throw new BackgroundTransientAiException("TIMEOUT", "DigitalOcean enrichment request timed out", ex);
            }
            throw new BackgroundTransientAiException("TRANSIENT_AI_ERROR", "DigitalOcean enrichment request failed", ex);
        }
    }

    private boolean isTransientStatus(int status) {
        return status == 502 || status == 503 || status == 504;
    }

    private boolean isTimeout(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("timed out")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private JsonNode parseBody(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            throw new InvalidDataException("Failed to parse AI response JSON: " + e.getOriginalMessage());
        }
    }

    private RestClient restClient(String baseUrl, String apiKey, int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private String content(JsonNode responseBody) {
        JsonNode choices = responseBody == null ? null : responseBody.path("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new InvalidDataException("DigitalOcean enrichment response does not contain choices");
        }
        String content = choices.get(0).path("message").path("content").asText(null);
        if (!StringUtils.hasText(content)) {
            throw new InvalidDataException("DigitalOcean enrichment response content is empty");
        }
        return content;
    }

    private AiUsage usage(JsonNode responseBody) {
        JsonNode usage = responseBody == null ? null : responseBody.path("usage");
        if (usage == null || usage.isMissingNode() || !usage.isObject()) {
            return null;
        }
        return new AiUsage(
                intValue(usage.path("prompt_tokens")),
                intValue(usage.path("completion_tokens")),
                intValue(usage.path("total_tokens"))
        );
    }

    private Integer intValue(JsonNode node) {
        return node != null && node.isIntegralNumber() ? node.asInt() : null;
    }
}
