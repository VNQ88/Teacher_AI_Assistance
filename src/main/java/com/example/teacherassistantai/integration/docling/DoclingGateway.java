package com.example.teacherassistantai.integration.docling;

public interface DoclingGateway {

    String parseFile(byte[] fileBytes,
                     String fileName,
                     String mimeType,
                     boolean doOcr,
                     boolean includeImages);

    String parseUrl(String sourceUrl,
                    boolean doOcr,
                    boolean includeImages);
}
