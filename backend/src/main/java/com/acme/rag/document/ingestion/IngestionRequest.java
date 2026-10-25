package com.acme.rag.document.ingestion;

import java.time.Instant;
import java.util.Set;

public record IngestionRequest(
        String documentId,
        String title,
        String department,
        String classification,
        Set<String> allowedRoles,
        String filename,
        byte[] content,
        Instant effectiveAt) {
    public IngestionRequest {
        allowedRoles = allowedRoles == null ? Set.of() : Set.copyOf(allowedRoles);
        content = content == null ? new byte[0] : content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
