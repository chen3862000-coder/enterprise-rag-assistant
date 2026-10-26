package com.acme.rag.retrieval;

import java.util.UUID;

public record RetrievalCandidate(
        String chunkId,
        UUID versionId,
        String documentId,
        String title,
        int version,
        String section,
        String text,
        double score,
        boolean vectorMatch,
        boolean keywordMatch,
        boolean exactMatch,
        double lexicalCoverage) {
    public RetrievalCandidate(String chunkId, UUID versionId, String documentId, String title, int version,
                              String section, String text, double score, boolean vectorMatch,
                              boolean keywordMatch, boolean exactMatch) {
        this(chunkId, versionId, documentId, title, version, section, text, score, vectorMatch,
                keywordMatch, exactMatch, keywordMatch ? 1.0 : 0.0);
    }

    public RetrievalCandidate(String chunkId, UUID versionId, String documentId, String title, int version,
                              String section, String text, double score, int vectorRank, int keywordRank, int exactRank) {
        this(chunkId, versionId, documentId, title, version, section, text, score, vectorRank > 0,
                keywordRank > 0, exactRank > 0);
    }
}
