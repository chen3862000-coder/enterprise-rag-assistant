package com.acme.rag.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EvaluationRunResult(
        UUID runId,
        String datasetVersion,
        String method,
        String embeddingModelId,
        int scenarioCount,
        EvaluationMetrics metrics,
        String status,
        Instant startedAt,
        Instant completedAt,
        List<EvaluationScenarioResult> scenarios) {
    public EvaluationRunResult {
        scenarios = List.copyOf(scenarios);
    }
}
