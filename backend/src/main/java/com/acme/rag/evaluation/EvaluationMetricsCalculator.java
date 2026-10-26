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
                accuracy(results.stream().map(EvaluationScenarioResult::evidenceGateCorrect).toList()),
                gateFalseAbstentionRate(results), gateFalseAdmissionRate(results), releaseQualityPassed(results),
                gateAblationAccuracy(results), prefixShadowMetrics(results));
    }

    private static double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static double accuracy(List<Boolean> values) {
        return (double) values.stream().filter(Boolean::booleanValue).count() / values.size();
    }

    private static java.util.Map<String, EvaluationMetrics.GateAblationMetrics> gateAblationAccuracy(
            List<EvaluationScenarioResult> results) {
        java.util.Set<String> policies = java.util.Set.of("CURRENT_COVERAGE", "VECTOR_ONLY",
                "LEXICAL_COVERAGE_ONLY", "COVERAGE_030", "COVERAGE_040");
        return policies.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                policy -> policy,
                policy -> ablationMetrics(results, policy)));
    }

    private static EvaluationMetrics.GateAblationMetrics ablationMetrics(
            List<EvaluationScenarioResult> results, String policy) {
        List<Boolean> pairs = results.stream().map(result -> {
            boolean relevantCandidateRetrieved = result.expectedDocumentIds().stream()
                    .anyMatch(result.retrievedDocumentIds()::contains);
            boolean expectedPass = result.expectedEvidenceGate() && relevantCandidateRetrieved;
            return result.gateDiagnostics().ablationDecisions().getOrDefault(policy, false) == expectedPass;
        }).toList();
        int truePass = 0;
        int falseAbstention = 0;
        int falseAdmission = 0;
        int trueAbstention = 0;
        for (int index = 0; index < results.size(); index++) {
            EvaluationScenarioResult result = results.get(index);
            boolean relevantCandidateRetrieved = result.expectedDocumentIds().stream()
                    .anyMatch(result.retrievedDocumentIds()::contains);
            boolean expectedPass = result.expectedEvidenceGate() && relevantCandidateRetrieved;
            boolean predictedPass = result.gateDiagnostics().ablationDecisions().getOrDefault(policy, false);
            if (expectedPass && predictedPass) {
                truePass++;
            } else if (expectedPass) {
                falseAbstention++;
            } else if (predictedPass) {
                falseAdmission++;
            } else {
                trueAbstention++;
            }
        }
        return new EvaluationMetrics.GateAblationMetrics(truePass, falseAbstention, falseAdmission, trueAbstention,
                accuracy(pairs), rate(falseAbstention, truePass + falseAbstention),
                rate(falseAdmission, falseAdmission + trueAbstention));
    }

    private static double rate(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private static double gateFalseAbstentionRate(List<EvaluationScenarioResult> results) {
        List<EvaluationScenarioResult> positive = results.stream()
                .filter(result -> result.expectedEvidenceGate() && result.expectedRelevantCandidate()).toList();
        return positive.isEmpty() ? 0.0 : rate((int) positive.stream()
                .filter(result -> !gatePredictedPass(result)).count(), positive.size());
    }

    private static double gateFalseAdmissionRate(List<EvaluationScenarioResult> results) {
        List<EvaluationScenarioResult> negative = results.stream()
                .filter(result -> !result.expectedEvidenceGate() || !result.expectedRelevantCandidate()).toList();
        return negative.isEmpty() ? 0.0 : rate((int) negative.stream()
                .filter(EvaluationMetricsCalculator::gatePredictedPass).count(), negative.size());
    }

    private static boolean releaseQualityPassed(List<EvaluationScenarioResult> results) {
        double falseAbstentionRate = gateFalseAbstentionRate(results);
        double falseAdmissionRate = gateFalseAdmissionRate(results);
        boolean enoughPositiveCases = results.stream()
                .filter(result -> result.expectedEvidenceGate() && result.expectedRelevantCandidate()).count() >= 10;
        boolean enoughNegativeCases = results.stream()
                .filter(result -> !result.expectedEvidenceGate() || !result.expectedRelevantCandidate()).count() >= 10;
        return results.stream().noneMatch(EvaluationScenarioResult::aclLeakage)
                && enoughPositiveCases && enoughNegativeCases
                && falseAbstentionRate <= com.acme.rag.retrieval.EvidenceSufficiencyGate.RELEASE_MAX_FALSE_ABSTENTION_RATE
                && falseAdmissionRate <= com.acme.rag.retrieval.EvidenceSufficiencyGate.RELEASE_MAX_FALSE_ADMISSION_RATE;
    }

    private static boolean gatePredictedPass(EvaluationScenarioResult result) {
        return result.expectedEvidenceGate() == result.evidenceGateCorrect();
    }

    private static EvaluationMetrics.PrefixShadowMetrics prefixShadowMetrics(List<EvaluationScenarioResult> results) {
        List<EvaluationScenarioResult> answerable = results.stream()
                .filter(result -> !result.expectedDocumentIds().isEmpty()).toList();
        int falseAbstentions = 0;
        int falseAdmissions = 0;
        int correct = 0;
        for (EvaluationScenarioResult result : results) {
            var shadow = result.gateDiagnostics().prefixShadow();
            boolean expectedPass = result.expectedEvidenceGate()
                    && result.expectedDocumentIds().stream().anyMatch(shadow.retrievedDocumentIds()::contains);
            if (expectedPass && !shadow.vectorGatePassed()) {
                falseAbstentions++;
            } else if (!expectedPass && shadow.vectorGatePassed()) {
                falseAdmissions++;
            } else {
                correct++;
            }
        }
        return new EvaluationMetrics.PrefixShadowMetrics(true,
                average(answerable.stream().map(result -> result.gateDiagnostics().prefixShadow().recallAt5()).toList()),
                average(answerable.stream().map(result -> result.gateDiagnostics().prefixShadow().reciprocalRank()).toList()),
                results.isEmpty() ? 0.0 : (double) correct / results.size(), falseAbstentions, falseAdmissions);
    }
}
