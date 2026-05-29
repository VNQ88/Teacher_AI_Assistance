package com.example.teacherassistantai.exception;

import java.time.Instant;

public class BackgroundTransientAiException extends RuntimeException {

    private final String errorType;
    private final Instant retryAfter;

    public BackgroundTransientAiException(String errorType, String message, Throwable cause) {
        this(errorType, message, null, cause);
    }

    public BackgroundTransientAiException(String errorType, String message, Instant retryAfter, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
        this.retryAfter = retryAfter;
    }

    public String getErrorType() {
        return errorType;
    }

    public Instant getRetryAfter() {
        return retryAfter;
    }
}
