package com.acme.rag.evaluation;

import com.acme.rag.citation.CitationResponseBuilder;
import com.acme.rag.retrieval.HybridRetrievalService;
import com.acme.rag.retrieval.RetrievalResult;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.acme.rag.citation.CitationEvidence;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public final class EvaluationScenarioEvaluator {
    private static final int RANK_CUTOFF = 5;
    private final HybridRetrievalService retrieval;
    private final CitationResponseBuilder citations;
    private final DemoRoleResolver roles;

    public EvaluationScenarioEvaluator(
            HybridRetrievalService retrieval,
            CitationResponseBuilder citations,
            DemoRoleResolver roles) {
        this.retrieval = retrieval;
        this.citations = citations;
        this.roles = roles;
    }

    public EvaluationScenarioResult evaluate(EvaluationScenario scenario) {
        return evaluate(scenario, new PrefixShadowEvaluator.PrefixScenarioResult(List.of(), 0.0, 0.0,
                false, false, 0.0));
    }

    public EvaluationScenarioResult evaluate(EvaluationScenario scenario,
                                             PrefixShadowEvaluator.PrefixScenarioResult shadow) {
        RetrievalResult retrieved = retrieval.retrieveForEvaluation(scenario.query(), roles.rolesFor(scenario.user()),
                Set.copyOf(scenario.expectedDocumentIds()));
        List<String> documentIds = retrieved.retrievedCandidates().stream()
                .map(chunk -> chunk.documentId()).distinct().toList();
        Set<String> retrievedSet = new HashSet<>(documentIds);
        List<String> missing = scenario.expectedDocumentIds().stream().filter(id -> !retrievedSet.contains(id)).toList();
        List<String> forbidden = scenario.mustNotRetrieveDocumentIds().stream().filter(retrievedSet::contains).toList();
        double recall = scenario.expectedDocumentIds().isEmpty() ? 0.0 : recallAtFive(
                scenario.expectedDocumentIds(), retrieved.retrievedCandidates().stream().limit(RANK_CUTOFF)
                        .map(chunk -> chunk.documentId()).toList());
        double reciprocalRank = reciprocalRank(scenario.expectedDocumentIds(), documentIds);
        Map<String, Integer> versions = retrieved.retrievedCandidates().stream().collect(Collectors.toMap(
                chunk -> chunk.documentId(), chunk -> chunk.version(), (first, ignored) -> first));
        boolean freshnessCorrect = freshnessCorrect(scenario, versions);
        List<CitationEvidence> supplied = new java.util.ArrayList<>();
        StringBuilder deterministicCitationCheck = new StringBuilder();
        for (int index = 0; index < Math.min(RANK_CUTOFF, retrieved.chunks().size()); index++) {
            String citationId = "S" + (index + 1);
            supplied.add(new CitationEvidence(citationId, retrieved.chunks().get(index)));
            deterministicCitationCheck.append('[').append(citationId).append("] ");
        }
        String sanitized = citations.stripUnsupportedCitationClaims(deterministicCitationCheck + "[S999]", supplied);
        boolean citationIdsRejected = !sanitized.contains("S999");
        boolean citationCorrect = citationIdsRejected;
        boolean evidenceGateCorrect = retrieved.evidenceSufficient() == scenario.evidenceGateExpected();
        var gateDiagnostic = retrieved.diagnostic();
        boolean relevantCandidateRetrieved = scenario.expectedDocumentIds().stream().anyMatch(documentIds::contains);
        var diagnostics = new EvaluationScenarioResult.GateDiagnostics(gateDiagnostic.candidateCount(),
                gateDiagnostic.bestVectorSimilarity(), gateDiagnostic.bestKeywordScore(),
                gateDiagnostic.bestExpectedVectorSimilarity(), gateDiagnostic.bestExpectedKeywordScore(),
                gateDiagnostic.expectedExactMatch(), gateDiagnostic.vectorPassingCandidateCount(),
                gateDiagnostic.keywordPassingCandidateCount(), gateDiagnostic.jointPassingCandidateCount(),
                gateDiagnostic.ablationDecisions(), gateDiagnostic.minimumLexicalCoverage(),
                gateDiagnostic.bestLexicalCoverage(),
                gateDiagnostic.bestExpectedLexicalCoverage(), gateDiagnostic.sensitiveRequest(),
                gateDiagnostic.intentPolicyRejection(), gateDiagnostic.roleScopeRejected(),
                new EvaluationScenarioResult.GateDiagnostics.PrefixShadowDiagnostics(shadow.retrievedDocumentIds(),
                        shadow.recallAt5(), shadow.reciprocalRank(), shadow.evidenceGatePassed(),
                        shadow.bestExpectedCosine()));
        boolean leakage = !forbidden.isEmpty();
        boolean passed = !leakage && freshnessCorrect && citationCorrect && evidenceGateCorrect;
        return new EvaluationScenarioResult(scenario.id(), scenario.category(), scenario.expectedDocumentIds(),
                documentIds, missing, forbidden, scenario.expectedVersion(), versions, reciprocalRank, recall,
                scenario.evidenceGateExpected(), relevantCandidateRetrieved, leakage,
                freshnessCorrect, citationCorrect, evidenceGateCorrect,
                retrieved.gateReason(), diagnostics, passed);
    }

    private static boolean hasFreshnessExpectation(EvaluationScenario scenario) {
        return scenario.expectedVersion() != null;
    }

    private static boolean freshnessCorrect(EvaluationScenario scenario, Map<String, Integer> versions) {
        return !hasFreshnessExpectation(scenario) || scenario.expectedDocumentIds().stream()
                .allMatch(id -> versions.containsKey(id) && versions.get(id) >= scenario.expectedVersion());
    }

    private static double recallAtFive(List<String> expected, List<String> ranked) {
        if (expected.isEmpty()) {
            return 1.0;
        }
        Set<String> found = ranked.stream().limit(RANK_CUTOFF).collect(Collectors.toSet());
        long count = expected.stream().filter(found::contains).count();
        return (double) count / expected.size();
    }

    private static double reciprocalRank(List<String> expected, List<String> ranked) {
        if (expected.isEmpty()) {
            return 1.0;
        }
        for (int index = 0; index < ranked.size(); index++) {
            if (expected.contains(ranked.get(index))) {
                return 1.0 / (index + 1);
            }
        }
        return 0.0;
    }

}
