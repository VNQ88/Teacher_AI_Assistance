package com.example.teacherassistantai.service;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class InternalCitationSanitizer {

    private static final String CHUNK_NUMBER_LIST = "\\d+(?:\\s*(?:,|and|va|và)\\s*\\d+)*";

    private static final Pattern BRACKETED_CHUNK_REFERENCE = Pattern.compile(
            "(?i)\\s*[\\(\\[]\\s*(?:chunks?\\s+" + CHUNK_NUMBER_LIST
                    + "|chunk\\s*id\\s*:?\\s*\\d+)\\s*[\\)\\]]"
    );
    private static final Pattern CHUNK_ID_REFERENCE = Pattern.compile(
            "(?i)\\bchunk\\s*id\\s*:?\\s*\\d+\\b"
    );
    private static final Pattern CHUNK_REFERENCE = Pattern.compile(
            "(?i)\\bchunks?\\s+" + CHUNK_NUMBER_LIST + "\\b\\s*,?"
    );

    public String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String sanitized = BRACKETED_CHUNK_REFERENCE.matcher(text).replaceAll("");
        sanitized = CHUNK_ID_REFERENCE.matcher(sanitized).replaceAll("");
        sanitized = CHUNK_REFERENCE.matcher(sanitized).replaceAll("");
        sanitized = sanitized.replaceAll("[ \\t]+([.,;:!?])", "$1");
        sanitized = sanitized.replaceAll("\\(\\s*\\)", "");
        sanitized = sanitized.replaceAll("\\[\\s*\\]", "");
        sanitized = sanitized.replaceAll("[ \\t]{2,}", " ");
        sanitized = sanitized.replaceAll("(?m)[ \\t]+$", "");
        sanitized = sanitized.replaceAll("\\n{3,}", "\n\n");
        return sanitized.trim();
    }
}
