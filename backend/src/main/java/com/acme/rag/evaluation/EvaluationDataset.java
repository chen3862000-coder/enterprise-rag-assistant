package com.acme.rag.evaluation;

import java.util.List;

public record EvaluationDataset(String version, List<EvaluationScenario> scenarios) {
    public EvaluationDataset {
        scenarios = List.copyOf(scenarios);
    }
}
