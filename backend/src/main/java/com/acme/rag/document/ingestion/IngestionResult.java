package com.acme.rag.document.ingestion;

import java.util.UUID;

/**
 * Outcome of ingestion. {@code jobId} is null for duplicate content; {@code metadataUpdated} indicates
 * whether a duplicate submission applied changed document metadata or permissions.
 */
public record IngestionResult(String documentId, UUID versionId, UUID jobId, int version, int chunkCount,
                              boolean duplicate, boolean metadataUpdated) {}
