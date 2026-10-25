package com.acme.rag.document.ingestion;

import java.util.UUID;

record VersionReservation(UUID versionId, int version, UUID jobId, boolean duplicate) {}
