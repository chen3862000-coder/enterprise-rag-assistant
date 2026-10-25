package com.acme.rag.citation;

public record Citation(String citationId, String documentId, String documentTitle, int version, String chunkId, String section) {}
