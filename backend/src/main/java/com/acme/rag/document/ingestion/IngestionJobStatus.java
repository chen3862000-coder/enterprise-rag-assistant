package com.acme.rag.document.ingestion;

import java.time.Instant;
import java.util.UUID;

/** Content-free job status exposed only after document ACL authorization. */
public record IngestionJobStatus(UUID jobId, String documentId, String documentTitle, String status,
                                 String errorMessage, int progressChunks, Integer totalChunks,
                                 String versionStatus, boolean active, Instant startedAt, Instant completedAt) {}
