package com.acme.rag.document.ingestion;

import java.time.Instant;
import java.util.UUID;

public record DocumentVersionSummary(UUID id, int version, String contentHash, String sourceFilename,
                                     Instant effectiveAt, String indexStatus, String embeddingModelId,
                                     int embeddingDimensions, Instant createdAt, boolean active) {}
