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
        String responseMode,
        Instant createdAt,
        com.acme.rag.retrieval.QueryPlanningDiagnostics queryPlanning) {

    public RagTrace(UUID id, String userId, long retrievalDurationMs, long generationDurationMs,
                    long totalDurationMs, int vectorCandidateCount, int keywordCandidateCount,
                    int exactCandidateCount, int contextChunkCount, int citationCount,
                    boolean evidenceSufficient, String evidenceGateReason, String modelProvider,
                    String modelId, String groundingStatus, String status, String errorCategory,
                    String responseMode, Instant createdAt) {
        this(id, userId, retrievalDurationMs, generationDurationMs, totalDurationMs,
                vectorCandidateCount, keywordCandidateCount, exactCandidateCount, contextChunkCount,
                citationCount, evidenceSufficient, evidenceGateReason, modelProvider, modelId,
                groundingStatus, status, errorCategory, responseMode, createdAt,
                com.acme.rag.retrieval.QueryPlanningDiagnostics.disabled());
    }
}
