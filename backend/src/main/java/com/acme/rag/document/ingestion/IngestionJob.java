package com.acme.rag.document.ingestion;

import java.nio.file.Path;
import java.util.UUID;

/** Durable worker claim; source paths are internal and never returned by status APIs. */
record IngestionJob(UUID id, String documentId, UUID versionId, Path sourcePath, String filename,
                    int progressChunks, int attemptCount, String workerId, int versionNumber) {}
