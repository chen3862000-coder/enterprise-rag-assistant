package com.acme.rag.document.ingestion;

import java.util.Set;

/** Durable, content-free document metadata needed for activation after process restart. */
record IngestionMetadata(String documentId, String title, String department, String classification, Set<String> roles) {}
