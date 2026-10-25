package com.acme.rag.citation;

public record SourcePreview(String chunkId, String documentId, String documentTitle, int version, String section, String text) {}
