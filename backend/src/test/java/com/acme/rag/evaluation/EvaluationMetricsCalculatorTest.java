package com.acme.rag.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvaluationMetricsCalculatorTest {
    @Test
    void excludesUnanswerableCasesFromRetrievalRecallAndMrrButScoresSharedEvidenceGate() {
        EvaluationScenarioResult fact = result("fact", List.of("doc"), 1.0, 1.0, false, true, true, true);
        EvaluationScenarioResult impossible = result("unknown", List.of(), 0.0, 0.0, false, true, true, true);

        EvaluationMetrics metrics = new EvaluationMetricsCalculator().calculate(List.of(fact, impossible));

        assertThat(metrics.recallAt5()).isEqualTo(1.0);
        assertThat(metrics.meanReciprocalRank()).isEqualTo(1.0);
        assertThat(metrics.evidenceGateAccuracy()).isEqualTo(1.0);
    }

    @Test
    void reportsEachGateAblationAgainstExplicitFixtureExpectations() {
        EvaluationScenarioResult base = result("false-abstention", List.of("doc"), 1.0, 1.0,
                false, true, true, false);
        var diagnostics = new EvaluationScenarioResult.GateDiagnostics(1, 0.7, 0.0, 0.7, 0.0,
                false, 1, 0, 0, Map.of("CURRENT_COVERAGE", false, "VECTOR_ONLY", true,
                "LEXICAL_COVERAGE_ONLY", false, "COVERAGE_030", false, "COVERAGE_040", false),
                0.7, 0.0, 0.0, false, false, false,
                new EvaluationScenarioResult.GateDiagnostics.PrefixShadowDiagnostics(List.of(), 0.0, 0.0, false, 0.0));
        EvaluationScenarioResult falseAbstention = new EvaluationScenarioResult(base.scenarioId(), base.category(),
                base.expectedDocumentIds(), List.of("doc"), base.missingDocumentIds(),
                base.forbiddenDocumentIds(), base.expectedVersion(), base.retrievedVersions(), base.reciprocalRank(),
                base.recallAt5(), true, true, base.aclLeakage(), base.freshnessCorrect(), base.citationIdValidationCorrect(),
                false, "NO_KEYWORD_MATCH", diagnostics, false);

        EvaluationMetrics metrics = new EvaluationMetricsCalculator().calculate(List.of(falseAbstention));

        assertThat(metrics.gateAblationMetrics().get("CURRENT_COVERAGE").falseAbstention()).isEqualTo(1);
        assertThat(metrics.gateAblationMetrics().get("VECTOR_ONLY").truePass()).isEqualTo(1);
    }

    @Test
    void reportsPrefixShadowRetrievalAndGateMetricsSeparately() {
        EvaluationScenarioResult base = result("prefix", List.of("doc"), 0.0, 0.0,
                false, true, true, false);
        var diagnostics = new EvaluationScenarioResult.GateDiagnostics(1, 0.6, 0.0, 0.6, 0.0,
                false, 1, 0, 0, Map.of("CURRENT_COVERAGE", false),
                0.0, 0.0, 0.0, false, false, false,
                new EvaluationScenarioResult.GateDiagnostics.PrefixShadowDiagnostics(
                        List.of("doc"), 1.0, 1.0, true, 0.68));
        EvaluationScenarioResult result = new EvaluationScenarioResult(base.scenarioId(), base.category(),
                base.expectedDocumentIds(), List.of("doc"), List.of(), List.of(), base.expectedVersion(),
                base.retrievedVersions(), base.reciprocalRank(), base.recallAt5(), true, true, false, true, true,
                false, "NO_KEYWORD_MATCH", diagnostics, false);

        EvaluationMetrics metrics = new EvaluationMetricsCalculator().calculate(List.of(result));

        assertThat(metrics.prefixShadowMetrics().available()).isTrue();
        assertThat(metrics.prefixShadowMetrics().recallAt5()).isEqualTo(1.0);
        assertThat(metrics.prefixShadowMetrics().falseAbstentionCount()).isZero();
        assertThat(metrics.prefixShadowMetrics().falseAdmissionCount()).isZero();
    }

    private static EvaluationScenarioResult result(String id, List<String> expected, double recall, double mrr,
                                                   boolean leakage, boolean freshness, boolean citation, boolean gateCorrect) {
        return new EvaluationScenarioResult(id, EvaluationCategory.FACT_LOOKUP, expected, List.of(), List.of(), List.of(),
                null, Map.of(), mrr, recall, gateCorrect, !expected.isEmpty(), leakage, freshness, citation, gateCorrect, "TEST",
                new EvaluationScenarioResult.GateDiagnostics(0, 0.0, 0.0, 0.0, 0.0, false,
                        0, 0, 0, Map.of(), 0.0, 0.0, 0.0, false, false, false,
                        new EvaluationScenarioResult.GateDiagnostics.PrefixShadowDiagnostics(
                        List.of(), 0.0, 0.0, false, 0.0)), true);
    }
}
