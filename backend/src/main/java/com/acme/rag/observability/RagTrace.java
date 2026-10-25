package com.acme.rag.observability;

import java.time.Instant;
import java.util.UUID;

public record RagTrace(
        UUID id,
        String userId,
        long retrievalDurationMs,
        long generationDurationMs,
        long totalDurationMs,
        int vectorCandidateCount,
        int keywordCandidateCount,
        int exactCandidateCount,
        int contextChunkCount,
        int citationCount,
        boolean evidenceSufficient,
        String evidenceGateReason,
        String modelProvider,
        String modelId,
        String groundingStatus,
        String status,
        String errorCategory,
        Instant createdAt) {}
