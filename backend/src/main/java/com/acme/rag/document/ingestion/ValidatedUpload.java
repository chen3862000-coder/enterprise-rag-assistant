package com.acme.rag.document.ingestion;

import java.time.Instant;
import java.util.Set;

record ValidatedUpload(
        String documentId,
        String title,
        String department,
        String classification,
        Set<String> roles,
        String filename,
        byte[] content,
        Instant effectiveAt,
        boolean seedManaged) {}
