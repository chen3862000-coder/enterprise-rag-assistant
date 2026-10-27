package com.acme.rag.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persisted offline retrieval evaluation. Its metrics cover retrieval, ACL, freshness, citation-ID,
 * and evidence-gate behavior; they do not measure generated-answer correctness or faithfulness.
 */
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
