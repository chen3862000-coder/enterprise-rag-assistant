package com.acme.rag.evaluation;

import java.time.Instant;
import java.util.UUID;
import java.util.Map;

public record EvaluationRunSummary(
        UUID runId,
        String requestedBy,
        String datasetVersion,
        String method,
        String embeddingModelId,
        Map<String, Object> retrievalConfiguration,
        int scenarioCount,
        EvaluationMetrics metrics,
        String status,
        Instant startedAt,
        Instant completedAt) {
    public EvaluationRunSummary {
        retrievalConfiguration = Map.copyOf(retrievalConfiguration);
    }
}
