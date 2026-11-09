package com.acme.rag.document.ingestion;

import java.time.Instant;
import java.util.List;

public record DocumentSummary(String id, String title, String department, String classification,
                              String status, Integer currentVersion, String indexStatus, Instant updatedAt,
                              List<String> allowedRoles) {
    public DocumentSummary {
        allowedRoles = List.copyOf(allowedRoles);
    }
}
