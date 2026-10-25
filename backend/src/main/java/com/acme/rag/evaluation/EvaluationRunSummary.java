package com.acme.rag.evaluation;

import java.time.Instant;
import java.util.UUID;

public record EvaluationRunSummary(
        UUID runId,
        String requestedBy,
        String datasetVersion,
        String method,
        String embeddingModelId,
        int scenarioCount,
        EvaluationMetrics metrics,
        String status,
        Instant startedAt,
        Instant completedAt) {}
