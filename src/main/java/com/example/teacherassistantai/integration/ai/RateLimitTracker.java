package com.example.teacherassistantai.integration.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class RateLimitTracker {

    private static final int LOW_REMAINING_THRESHOLD = 50;

    private final AtomicInteger remaining = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicReference<Instant> resetAt = new AtomicReference<>(Instant.EPOCH);

    public void update(int newRemaining, long resetEpochSeconds) {
        remaining.set(newRemaining);
        resetAt.set(Instant.ofEpochSecond(resetEpochSeconds));
        if (newRemaining < LOW_REMAINING_THRESHOLD) {
            log.warn("Rate limit approaching: remaining={} resetAt={}", newRemaining, resetAt.get());
        }
    }

    /**
     * Blocks the calling thread until the reset timestamp passes if quota is critically low.
     */
    public void waitIfNeeded() {
        if (remaining.get() >= LOW_REMAINING_THRESHOLD) return;
        Instant reset = resetAt.get();
        Instant now = Instant.now();
        if (reset.isAfter(now)) {
            long waitMs = reset.toEpochMilli() - now.toEpochMilli() + 3_000L;
            log.info("Rate limit proactive pause: waiting {}ms until reset={}", waitMs, reset);
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public Instant getResetAt() { return resetAt.get(); }
    public int getRemaining()   { return remaining.get(); }
}
