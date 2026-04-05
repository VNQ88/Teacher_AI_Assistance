package com.example.teacherassistantai.common.enumerate;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum ResourceType {
    AVATAR,
    LESSON,
    OTHER;

    @JsonCreator
    public static ResourceType from(String value) {
        if (value == null) return null;
        return ResourceType.valueOf(value.trim().toUpperCase());
    }
}
