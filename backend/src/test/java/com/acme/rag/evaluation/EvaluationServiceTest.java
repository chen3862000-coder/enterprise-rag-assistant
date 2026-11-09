package com.acme.rag.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.rag.observability.RagMetrics;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class EvaluationServiceTest {
    @Test
    void persistsAndReturnsIndependentDevelopmentAndHeldoutReports() {
        EvaluationDatasetLoader loader = new EvaluationDatasetLoader(new ObjectMapper());
        EvaluationScenarioEvaluator evaluator = mock(EvaluationScenarioEvaluator.class);
        PrefixShadowEvaluator shadowEvaluator = mock(PrefixShadowEvaluator.class);
        EvaluationRunRepository runs = mock(EvaluationRunRepository.class);
        RagMetrics metrics = mock(RagMetrics.class);
        when(shadowEvaluator.evaluate(any())).thenAnswer(invocation -> {
            List<EvaluationScenario> dataset = invocation.getArgument(0);
            Map<String, PrefixShadowEvaluator.PrefixScenarioResult> results = dataset.stream().collect(
                    java.util.stream.Collectors.toUnmodifiableMap(EvaluationScenario::id,
                            scenario -> new PrefixShadowEvaluator.PrefixScenarioResult(List.of(), 0.0, 0.0,
                                    false, false, 0.0)));
            return new PrefixShadowEvaluator.PrefixShadowResult(dataset.size(), 0.0, 0.0, 1.0, 0, 0, results);
        });
        when(evaluator.evaluate(any(EvaluationScenario.class), any())).thenAnswer(invocation ->
                scenarioResult(invocation.getArgument(0)));

        EvaluationService service = new EvaluationService(loader, evaluator, shadowEvaluator,
                new EvaluationMetricsCalculator(), runs, metrics, 10, 8, 60,
                0.35, 0.0005, "test-embedding", false, "deepseek", "", false);
        EvaluationReports reports = service.run("carol");

        assertThat(reports.development().datasetVersion()).isEqualTo("2026-10-p1-v5");
        assertThat(reports.development().scenarioCount()).isEqualTo(63);
        assertThat(reports.heldout().datasetVersion()).isEqualTo("heldout-paraphrases-v3");
        assertThat(reports.heldout().scenarioCount()).isEqualTo(40);
        assertThat(reports.heldout().metrics().releaseQualityPassed()).isNull();
        assertThat(reports.heldout().metrics().prefixShadowMetrics().available()).isFalse();
        ArgumentCaptor<EvaluationRunResult> saved = ArgumentCaptor.forClass(EvaluationRunResult.class);
        verify(runs, org.mockito.Mockito.times(2)).save(saved.capture(), eq("carol"), any());
        assertThat(saved.getAllValues()).extracting(EvaluationRunResult::datasetVersion)
                .containsExactly("2026-10-p1-v5", "heldout-paraphrases-v3");
        assertThat(saved.getAllValues()).extracting(EvaluationRunResult::scenarioCount).containsExactly(63, 40);
        assertThat(saved.getAllValues()).extracting(run -> run.retrievalConfiguration().get("queryPlannerExecutionMode"))
                .containsOnly("DETERMINISTIC_ONLY");
    }

    @Test
    void prefixShadowIsOptInAndDoesNotRunDuringStandardEvaluation() {
        EvaluationDatasetLoader loader = new EvaluationDatasetLoader(new ObjectMapper());
        EvaluationScenarioEvaluator evaluator = mock(EvaluationScenarioEvaluator.class);
        PrefixShadowEvaluator shadowEvaluator = mock(PrefixShadowEvaluator.class);
        EvaluationRunRepository runs = mock(EvaluationRunRepository.class);
        RagMetrics metrics = mock(RagMetrics.class);
        when(evaluator.evaluate(any(EvaluationScenario.class), any())).thenAnswer(invocation ->
                scenarioResult(invocation.getArgument(0)));

        EvaluationService service = new EvaluationService(loader, evaluator, shadowEvaluator,
                new EvaluationMetricsCalculator(), runs, metrics, 10, 8, 60,
                0.35, 0.0005, "test-embedding", false, "deepseek", "", false);

        EvaluationReports reports = service.run("carol");

        org.mockito.Mockito.verifyNoInteractions(shadowEvaluator);
        assertThat(reports.development().metrics().prefixShadowMetrics().available()).isFalse();
        assertThat(reports.heldout().metrics().prefixShadowMetrics().available()).isFalse();
    }

    private static EvaluationScenarioResult scenarioResult(EvaluationScenario scenario) {
        return new EvaluationScenarioResult(scenario.id(), scenario.category(), scenario.expectedDocumentIds(),
                List.of(), scenario.expectedDocumentIds(), List.of(), scenario.expectedVersion(), Map.of(),
                0.0, 0.0, scenario.evidenceGateExpected(), false, false, false, true,
                scenario.evidenceGateExpected(), "INSUFFICIENT_EVIDENCE",
                new EvaluationScenarioResult.GateDiagnostics(0, 0.0, 0.0, 0.0, 0.0, false,
                        0, 0, 0, Map.of(), 0.0, 0.0, 0.0, false, false, false,
                        new EvaluationScenarioResult.GateDiagnostics.PrefixShadowDiagnostics(
                                List.of(), 0.0, 0.0, false, 0.0)), true);
    }
}
