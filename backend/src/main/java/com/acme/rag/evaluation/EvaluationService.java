package com.acme.rag.evaluation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import com.acme.rag.observability.RagMetrics;
import org.springframework.stereotype.Service;

@Service
public class EvaluationService {
    private final EvaluationDatasetLoader datasetLoader;
    private final EvaluationScenarioEvaluator evaluator;
    private final EvaluationMetricsCalculator metricsCalculator;
    private final EvaluationRunRepository runs;
    private final RagMetrics metrics;
    private final int candidateDepth;
    private final int fusedDepth;
    private final int rrfConstant;
    private final double minimumVectorSimilarity;
    private final double minimumKeywordScore;
    private final String embeddingModelId;

    public EvaluationService(
            EvaluationDatasetLoader datasetLoader,
            EvaluationScenarioEvaluator evaluator,
            EvaluationMetricsCalculator metricsCalculator,
            EvaluationRunRepository runs,
            RagMetrics metrics,
            @Value("${app.retrieval.candidate-depth:10}") int candidateDepth,
            @Value("${app.retrieval.fused-depth:8}") int fusedDepth,
            @Value("${app.retrieval.rrf-constant:60}") int rrfConstant,
            @Value("${app.retrieval.minimum-vector-similarity:0.35}") double minimumVectorSimilarity,
            @Value("${app.retrieval.minimum-keyword-score:0.0005}") double minimumKeywordScore,
            @Value("${app.ingestion.embedding-model-id:nomic-embed-text}") String embeddingModelId) {
        this.datasetLoader = datasetLoader;
        this.evaluator = evaluator;
        this.metricsCalculator = metricsCalculator;
        this.runs = runs;
        this.metrics = metrics;
        this.candidateDepth = candidateDepth;
        this.fusedDepth = fusedDepth;
        this.rrfConstant = rrfConstant;
        this.minimumVectorSimilarity = minimumVectorSimilarity;
        this.minimumKeywordScore = minimumKeywordScore;
        this.embeddingModelId = embeddingModelId;
    }

    public EvaluationRunResult run(String requestedBy) {
        Instant started = Instant.now();
        EvaluationDataset dataset = datasetLoader.load();
        java.util.ArrayList<EvaluationScenarioResult> evaluated = new java.util.ArrayList<>();
        for (EvaluationScenario scenario : dataset.scenarios()) {
            evaluated.add(evaluator.evaluate(scenario));
        }
        List<EvaluationScenarioResult> results = List.copyOf(evaluated);
        Instant completed = Instant.now();
        EvaluationMetrics calculatedMetrics = metricsCalculator.calculate(results);
        Map<String, Object> config = retrievalConfiguration();
        EvaluationRunResult result = new EvaluationRunResult(UUID.randomUUID(), dataset.version(), "OFFLINE_RETRIEVAL",
                embeddingModelId, config, results.size(), calculatedMetrics, "SUCCEEDED", started, completed, results);
        runs.save(result, requestedBy, config);
        metrics.evaluationRun();
        return result;
    }

    public EvaluationRunResult get(UUID runId, String requestedBy) {
        return runs.find(runId, requestedBy);
    }

    public List<EvaluationRunSummary> recent(int limit) {
        return runs.recent(limit);
    }

    private Map<String, Object> retrievalConfiguration() {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("candidateDepth", candidateDepth);
        configuration.put("fusedDepth", fusedDepth);
        configuration.put("rrfConstant", rrfConstant);
        configuration.put("minimumVectorSimilarity", minimumVectorSimilarity);
        configuration.put("minimumKeywordScore", minimumKeywordScore);
        return Map.copyOf(configuration);
    }
}
