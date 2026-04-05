package com.example.teacherassistantai.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ChunkMetadataBuilder {

    public Map<String, Object> buildJsonb(Integer pageFrom,
                                          Integer pageTo,
                                          String sectionHeader,
                                          String chunkType,
                                          Integer charCount) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("pageFrom", pageFrom);
        metadata.put("pageTo", pageTo);
        metadata.put("sectionHeader", sectionHeader);
        metadata.put("chunkType", chunkType);
        metadata.put("charCount", charCount);
        return metadata;
    }
}
