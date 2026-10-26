package com.acme.rag.evaluation;

import com.acme.rag.retrieval.EvidenceSufficiencyGate;
import com.acme.rag.retrieval.HybridRetrievalRepository;
import com.acme.rag.retrieval.RankedChunk;
import com.acme.rag.retrieval.ReciprocalRankFusion;
import com.acme.rag.retrieval.RetrievalCandidate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

@Component
public final class PrefixShadowEvaluator {
    private static final String DOCUMENT_PREFIX = "search_document: ";
    private static final String QUERY_PREFIX = "search_query: ";
    private final EmbeddingModel embeddings;
    private final HybridRetrievalRepository repository;
    private final ReciprocalRankFusion fusion;
    private final EvidenceSufficiencyGate gate;
    private final DemoRoleResolver roleResolver;

    public PrefixShadowEvaluator(EmbeddingModel embeddings, HybridRetrievalRepository repository,
                                 ReciprocalRankFusion fusion, EvidenceSufficiencyGate gate,
                                 DemoRoleResolver roleResolver) {
        this.embeddings = embeddings;
        this.repository = repository;
        this.fusion = fusion;
        this.gate = gate;
        this.roleResolver = roleResolver;
    }

    public PrefixShadowResult evaluate(List<EvaluationScenario> fixtures) {
        Map<Set<String>, List<EmbeddedChunk>> authorizedVectors = new HashMap<>();
        Map<String, PrefixScenarioResult> results = new HashMap<>();
        for (EvaluationScenario scenario : fixtures) {
            Set<String> roles = Set.copyOf(roleResolver.rolesFor(scenario.user()));
            List<EmbeddedChunk> documents = authorizedVectors.computeIfAbsent(roles, this::embedAuthorizedChunks);
            float[] queryVector = embeddings.embed(QUERY_PREFIX + scenario.query());
            List<RankedChunk> ranked = rank(documents, queryVector);
            EvidenceSufficiencyGate.Decision decision = gate.evaluate(scenario.query(), ranked,
                    Set.copyOf(scenario.expectedDocumentIds()));
            List<String> rankedDocumentIds = ranked.stream().map(RankedChunk::documentId).distinct().toList();
            Set<String> topFive = new HashSet<>(rankedDocumentIds.stream().limit(5).toList());
            int relevantTopFive = (int) scenario.expectedDocumentIds().stream().filter(topFive::contains).count();
            double recall = scenario.expectedDocumentIds().isEmpty() ? 0.0
                    : (double) relevantTopFive / scenario.expectedDocumentIds().size();
            double reciprocalRank = reciprocalRank(scenario.expectedDocumentIds(), rankedDocumentIds);
            boolean relevantCandidateRetrieved = scenario.expectedDocumentIds().stream()
                    .anyMatch(rankedDocumentIds::contains);
            boolean expectedPass = scenario.evidenceGateExpected() && relevantCandidateRetrieved;
            results.put(scenario.id(), new PrefixScenarioResult(rankedDocumentIds, recall, reciprocalRank,
                    decision.diagnostic().ablationDecisions().getOrDefault("VECTOR_ONLY", false), expectedPass,
                    decision.diagnostic().bestExpectedVectorSimilarity()));
        }
        return PrefixShadowResult.from(fixtures, results);
    }

    private List<EmbeddedChunk> embedAuthorizedChunks(Set<String> roles) {
        List<RetrievalCandidate> authorized = repository.authorizedChunks(roles);
        if (authorized.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = embeddings.embed(authorized.stream()
                .map(candidate -> DOCUMENT_PREFIX + candidate.text()).toList());
        if (vectors.size() != authorized.size()) {
            throw new IllegalStateException("Prefix shadow embedding count does not match authorized chunks");
        }
        List<EmbeddedChunk> embedded = new ArrayList<>(authorized.size());
        for (int index = 0; index < authorized.size(); index++) {
            embedded.add(new EmbeddedChunk(authorized.get(index), vectors.get(index)));
        }
        return List.copyOf(embedded);
    }

    private List<RankedChunk> rank(List<EmbeddedChunk> documents, float[] queryVector) {
        List<RetrievalCandidate> ranked = new ArrayList<>(documents.size());
        for (EmbeddedChunk embedded : documents) {
            RetrievalCandidate candidate = embedded.candidate();
            double similarity = dot(queryVector, embedded.vector());
            ranked.add(new RetrievalCandidate(candidate.chunkId(), candidate.versionId(), candidate.documentId(),
                    candidate.title(), candidate.version(), candidate.section(), candidate.text(), similarity,
                    true, false, candidate.exactMatch()));
        }
        List<RetrievalCandidate> topCandidates = ranked.stream()
                .sorted(java.util.Comparator.comparingDouble(RetrievalCandidate::score).reversed()
                        .thenComparing(RetrievalCandidate::chunkId))
                .limit(10).toList();
        return fusion.fuse(List.of(topCandidates));
    }

    private record EmbeddedChunk(RetrievalCandidate candidate, float[] vector) {}

    private static double dot(float[] left, float[] right) {
        if (left.length != right.length) {
            throw new IllegalStateException("Prefix shadow query and document dimensions do not match");
        }
        double score = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < left.length; index++) {
            score += (double) left[index] * right[index];
            leftNorm += (double) left[index] * left[index];
            rightNorm += (double) right[index] * right[index];
        }
        return leftNorm == 0.0 || rightNorm == 0.0 ? 0.0 : score / Math.sqrt(leftNorm * rightNorm);
    }

    private static double reciprocalRank(List<String> expected, List<String> ranked) {
        for (int index = 0; index < ranked.size(); index++) {
            if (expected.contains(ranked.get(index))) {
                return 1.0 / (index + 1);
            }
        }
        return 0.0;
    }

    public record PrefixScenarioResult(List<String> retrievedDocumentIds, double recallAt5, double reciprocalRank,
                                      boolean evidenceGatePassed, boolean expectedEvidenceGate,
                                      double bestExpectedCosine) {
        public PrefixScenarioResult {
            retrievedDocumentIds = List.copyOf(retrievedDocumentIds);
        }
    }

    public record PrefixShadowResult(int scenarioCount, double recallAt5, double meanReciprocalRank,
                                     double evidenceGateAccuracy, int falseAbstentionCount,
                                     int falseAdmissionCount, Map<String, PrefixScenarioResult> scenarios) {
        public PrefixShadowResult {
            scenarios = Map.copyOf(scenarios);
        }

        private static PrefixShadowResult from(List<EvaluationScenario> fixtures,
                                               Map<String, PrefixScenarioResult> evaluated) {
            List<EvaluationScenario> answerable = fixtures.stream()
                    .filter(scenario -> !scenario.expectedDocumentIds().isEmpty()).toList();
            double recall = answerable.stream().map(EvaluationScenario::id).map(evaluated::get)
                    .mapToDouble(PrefixScenarioResult::recallAt5).average().orElse(0.0);
            double mrr = answerable.stream().map(EvaluationScenario::id).map(evaluated::get)
                    .mapToDouble(PrefixScenarioResult::reciprocalRank).average().orElse(0.0);
            int falseAbstentions = 0;
            int falseAdmissions = 0;
            int correct = 0;
            for (EvaluationScenario scenario : fixtures) {
                PrefixScenarioResult result = evaluated.get(scenario.id());
                if (result.expectedEvidenceGate() && !result.evidenceGatePassed()) {
                    falseAbstentions++;
                } else if (!result.expectedEvidenceGate() && result.evidenceGatePassed()) {
                    falseAdmissions++;
                } else {
                    correct++;
                }
            }
            return new PrefixShadowResult(fixtures.size(), recall, mrr,
                    (double) correct / fixtures.size(), falseAbstentions, falseAdmissions, evaluated);
        }
    }
}
