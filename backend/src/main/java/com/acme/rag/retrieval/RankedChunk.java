package com.acme.rag.retrieval;

import java.util.UUID;

public record RankedChunk(
        String chunkId,
        UUID versionId,
        String documentId,
        String title,
        int version,
        String section,
        String text,
        double score,
        double vectorSimilarity,
        double keywordScore,
        boolean exactMatch,
        double lexicalCoverage) {
    public RankedChunk(String chunkId, UUID versionId, String documentId, String title, int version,
                       String section, String text, double score, double vectorSimilarity,
                       double keywordScore, boolean exactMatch) {
        this(chunkId, versionId, documentId, title, version, section, text, score,
                vectorSimilarity, keywordScore, exactMatch, keywordScore > 0.0 ? 1.0 : 0.0);
    }

    public RankedChunk(String chunkId, UUID versionId, String documentId, String title, int version,
                       String section, String text, double score) {
        this(chunkId, versionId, documentId, title, version, section, text, score, 0.0, 0.0, false, 0.0);
    }
}
