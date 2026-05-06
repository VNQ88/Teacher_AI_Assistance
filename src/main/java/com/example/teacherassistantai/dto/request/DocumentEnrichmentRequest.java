package com.example.teacherassistantai.dto.request;

import com.example.teacherassistantai.common.enumerate.DocumentNodeArtifactType;
import lombok.Data;

import java.util.List;

@Data
public class DocumentEnrichmentRequest {

    private List<DocumentNodeArtifactType> artifactTypes;

    private Boolean forceRegenerate = Boolean.FALSE;
}
