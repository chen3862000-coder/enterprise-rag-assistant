package com.acme.rag.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvaluationMetricsCalculatorTest {
    @Test
    void excludesUnanswerableScenariosFromRetrievalRecallAndMrr() {
        EvaluationScenarioResult fact = result("fact", List.of("doc"), 1.0, 1.0, false, true, true, true);
        EvaluationScenarioResult impossible = result("unknown", List.of(), 0.0, 0.0, false, true, true, true);

        EvaluationMetrics metrics = new EvaluationMetricsCalculator().calculate(List.of(fact, impossible));

        assertThat(metrics.recallAt5()).isEqualTo(1.0);
        assertThat(metrics.meanReciprocalRank()).isEqualTo(1.0);
        assertThat(metrics.abstentionAccuracy()).isEqualTo(1.0);
    }

    private static EvaluationScenarioResult result(String id, List<String> expected, double recall, double mrr,
                                                   boolean leakage, boolean freshness, boolean citation, boolean abstention) {
        return new EvaluationScenarioResult(id, EvaluationCategory.FACT_LOOKUP, expected, List.of(), List.of(), List.of(),
                null, Map.of(), mrr, recall, leakage, freshness, citation, abstention, true);
    }
}
