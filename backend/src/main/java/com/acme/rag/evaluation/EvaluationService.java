package com.acme.rag.evaluation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import com.acme.rag.observability.RagMetrics;
import com.acme.rag.retrieval.EvidenceSufficiencyGate;
import org.springframework.stereotype.Service;

/** Runs and persists deterministic offline retrieval/gate evaluations and their run-specific configuration. */
@Service
public class EvaluationService {
    private final EvaluationDatasetLoader datasetLoader;
    private final EvaluationScenarioEvaluator evaluator;
    private final PrefixShadowEvaluator prefixShadowEvaluator;
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
            PrefixShadowEvaluator prefixShadowEvaluator,
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
        this.prefixShadowEvaluator = prefixShadowEvaluator;
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

    /** Evaluates the checked-in dataset without calling the answer-generation model and persists the measured run. */
    public EvaluationRunResult run(String requestedBy) {
        Instant started = Instant.now();
        EvaluationDataset dataset = datasetLoader.load();
        PrefixShadowEvaluator.PrefixShadowResult shadow = prefixShadowEvaluator.evaluate(dataset.scenarios());
        java.util.ArrayList<EvaluationScenarioResult> evaluated = new java.util.ArrayList<>();
        for (EvaluationScenario scenario : dataset.scenarios()) {
            evaluated.add(evaluator.evaluate(scenario, shadow.scenarios().get(scenario.id())));
        }
        List<EvaluationScenarioResult> results = List.copyOf(evaluated);
        Instant completed = Instant.now();
        EvaluationMetrics baseMetrics = metricsCalculator.calculate(results);
        EvaluationMetrics calculatedMetrics = new EvaluationMetrics(baseMetrics.recallAt5(),
                baseMetrics.meanReciprocalRank(), baseMetrics.aclLeakageCount(), baseMetrics.freshnessAccuracy(),
                baseMetrics.citationIdValidationAccuracy(), baseMetrics.evidenceGateAccuracy(),
                baseMetrics.evidenceGateFalseAbstentionRate(), baseMetrics.evidenceGateFalseAdmissionRate(),
                baseMetrics.releaseQualityPassed(),
                baseMetrics.gateAblationMetrics(), new EvaluationMetrics.PrefixShadowMetrics(true, shadow.recallAt5(),
                shadow.meanReciprocalRank(), shadow.evidenceGateAccuracy(), shadow.falseAbstentionCount(),
                shadow.falseAdmissionCount()));
        Map<String, Object> config = retrievalConfiguration();
        EvaluationRunResult result = new EvaluationRunResult(UUID.randomUUID(), dataset.version(), "OFFLINE_RETRIEVAL",
                embeddingModelId, config, results.size(), calculatedMetrics, "SUCCEEDED", started, completed, results);
        runs.save(result, requestedBy, config);
        metrics.evaluationRun();
        return result;
    }

    /** Loads a persisted run in the scope enforced by the evaluation repository. */
    public EvaluationRunResult get(UUID runId, String requestedBy) {
        return runs.find(runId, requestedBy);
    }

    /** Returns recent persisted run summaries, subject to repository-enforced limit bounds. */
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
        configuration.put("keywordQueryMode", "OR over PostgreSQL English stemmed terms");
        configuration.put("gatePolicy", "same-chunk vector threshold + OR lexical score + adaptive term coverage + fail-closed intent/scope guards");
        configuration.put("minimumLexicalCoverageShort", 0.30);
        configuration.put("minimumLexicalCoverageMedium", 0.35);
        configuration.put("minimumLexicalCoverageLong", 0.40);
        configuration.put("releaseMaximumFalseAbstentionRate", EvidenceSufficiencyGate.RELEASE_MAX_FALSE_ABSTENTION_RATE);
        configuration.put("releaseMaximumFalseAdmissionRate", EvidenceSufficiencyGate.RELEASE_MAX_FALSE_ADMISSION_RATE);
        configuration.put("prefixShadow", "Nomic search_query/search_document over authorized in-memory chunks");
        configuration.put("prefixShadowDocumentPrefix", "search_document: ");
        configuration.put("prefixShadowQueryPrefix", "search_query: ");
        configuration.put("prefixShadowDenseDepth", 10);
        configuration.put("gateReleaseMaxFalseAbstentionRate", EvidenceSufficiencyGate.RELEASE_MAX_FALSE_ABSTENTION_RATE);
        configuration.put("gateReleaseMaxFalseAdmissionRate", EvidenceSufficiencyGate.RELEASE_MAX_FALSE_ADMISSION_RATE);
        return Map.copyOf(configuration);
    }
}
