package com.example.teacherassistantai.integration.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;

class DigitalOceanTokenRateLimitHeaderParserTest {

    private final DigitalOceanTokenRateLimitHeaderParser parser = new DigitalOceanTokenRateLimitHeaderParser();

    @Test
    void parse_readsTokenHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("x-ratelimit-limit-tokens-per-day", "5000000");
        headers.add("x-ratelimit-remaining-tokens-per-day", "4999000");
        headers.add("x-ratelimit-limit-tokens-per-minute", "100000");
        headers.add("x-ratelimit-remaining-tokens-per-minute", "99000");

        AiRateLimitSnapshot snapshot = parser.parse(headers);

        assertThat(snapshot.limitTokensPerDay()).isEqualTo(5_000_000L);
        assertThat(snapshot.remainingTokensPerDay()).isEqualTo(4_999_000L);
        assertThat(snapshot.limitTokensPerMinute()).isEqualTo(100_000L);
        assertThat(snapshot.remainingTokensPerMinute()).isEqualTo(99_000L);
        assertThat(snapshot.observedAt()).isNotNull();
    }

    @Test
    void parse_toleratesMissingAndMalformedHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("x-ratelimit-limit-tokens-per-day", "bad");

        AiRateLimitSnapshot snapshot = parser.parse(headers);

        assertThat(snapshot.limitTokensPerDay()).isNull();
        assertThat(snapshot.remainingTokensPerDay()).isNull();
        assertThat(snapshot.observedAt()).isNotNull();
    }
}
