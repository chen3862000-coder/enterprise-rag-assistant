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
        int vectorRank,
        int keywordRank,
        int exactRank) {}
