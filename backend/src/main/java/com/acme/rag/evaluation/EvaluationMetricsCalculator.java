package com.acme.rag.evaluation;

import java.util.List;

@org.springframework.stereotype.Component
public final class EvaluationMetricsCalculator {
    public EvaluationMetrics calculate(List<EvaluationScenarioResult> results) {
        if (results.isEmpty()) {
            return new EvaluationMetrics(0.0, 0.0, 0, 0.0, 0.0, 0.0);
        }
        List<EvaluationScenarioResult> answerable = results.stream()
                .filter(result -> !result.expectedDocumentIds().isEmpty()).toList();
        List<EvaluationScenarioResult> freshness = results.stream()
                .filter(result -> result.expectedVersion() != null).toList();
        return new EvaluationMetrics(
                average(answerable.stream().map(EvaluationScenarioResult::recallAt5).toList()),
                average(answerable.stream().map(EvaluationScenarioResult::reciprocalRank).toList()),
                (int) results.stream().filter(EvaluationScenarioResult::aclLeakage).count(),
                freshness.isEmpty() ? 0.0 : accuracy(freshness.stream().map(EvaluationScenarioResult::freshnessCorrect).toList()),
                accuracy(results.stream().map(EvaluationScenarioResult::citationIdValidationCorrect).toList()),
                accuracy(results.stream().map(EvaluationScenarioResult::evidenceGateCorrect).toList()));
    }

    private static double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static double accuracy(List<Boolean> values) {
        return (double) values.stream().filter(Boolean::booleanValue).count() / values.size();
    }
}
