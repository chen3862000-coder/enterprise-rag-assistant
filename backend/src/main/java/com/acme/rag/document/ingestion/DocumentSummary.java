package com.acme.rag.document.ingestion;

import java.time.Instant;

public record DocumentSummary(String id, String title, String department, String classification,
                              String status, Integer currentVersion, String indexStatus, Instant updatedAt) {}
