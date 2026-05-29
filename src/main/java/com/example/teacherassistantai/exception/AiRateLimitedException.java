package com.example.teacherassistantai.exception;

public class AiRateLimitedException extends RuntimeException {

    public AiRateLimitedException(String message) {
        super(message);
    }
}
