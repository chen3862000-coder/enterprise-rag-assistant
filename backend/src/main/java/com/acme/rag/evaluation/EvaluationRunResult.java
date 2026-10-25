package com.acme.rag.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record EvaluationRunResult(
        UUID runId,
        String datasetVersion,
        String method,
        String embeddingModelId,
        Map<String, Object> retrievalConfiguration,
        int scenarioCount,
        EvaluationMetrics metrics,
        String status,
        Instant startedAt,
        Instant completedAt,
        List<EvaluationScenarioResult> scenarios) {
    public EvaluationRunResult {
        retrievalConfiguration = Map.copyOf(retrievalConfiguration);
        scenarios = List.copyOf(scenarios);
    }
}
