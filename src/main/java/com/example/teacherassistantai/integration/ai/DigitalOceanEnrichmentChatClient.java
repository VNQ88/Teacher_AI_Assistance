package com.example.teacherassistantai.integration.ai;

import com.example.teacherassistantai.config.RagProperties;
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
import org.springframework.web.client.RestClientResponseException;

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
        RagProperties.Ai.EnrichmentAi enrichment = ragProperties.getAi().getEnrichment();
        if (!StringUtils.hasText(enrichment.getApiKey())) {
            throw new InvalidDataException("DigitalOcean enrichment API key is not configured");
        }

        RestClient restClient = restClient(route.baseUrl(), enrichment.getApiKey(), enrichment.getTimeoutSeconds());
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
            throw ex;
        }
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
