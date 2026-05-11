package com.example.teacherassistantai.exception;

import java.time.Instant;

public class BackgroundRateLimitedException extends RuntimeException {

    private final Instant pausedUntil;

    public BackgroundRateLimitedException(Instant pausedUntil) {
        super("Background AI requests paused until " + pausedUntil);
        this.pausedUntil = pausedUntil;
    }

    public Instant getPausedUntil() {
        return pausedUntil;
    }
}
