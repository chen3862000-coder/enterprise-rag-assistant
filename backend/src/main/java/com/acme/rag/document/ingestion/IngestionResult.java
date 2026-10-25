package com.acme.rag.document.ingestion;

import java.util.UUID;

public record IngestionResult(String documentId, UUID versionId, UUID jobId, int version, int chunkCount, boolean duplicate) {}
