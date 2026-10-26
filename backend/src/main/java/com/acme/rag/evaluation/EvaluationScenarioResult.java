package com.acme.rag.evaluation;

import java.util.List;
import java.util.Map;

public record EvaluationScenarioResult(
        String scenarioId,
        EvaluationCategory category,
        List<String> expectedDocumentIds,
        List<String> retrievedDocumentIds,
        List<String> missingDocumentIds,
        List<String> forbiddenDocumentIds,
        Integer expectedVersion,
        Map<String, Integer> retrievedVersions,
        double reciprocalRank,
        double recallAt5,
        boolean expectedEvidenceGate,
        boolean expectedRelevantCandidate,
        boolean aclLeakage,
        boolean freshnessCorrect,
        boolean citationIdValidationCorrect,
        boolean evidenceGateCorrect,
        String gateReason,
        GateDiagnostics gateDiagnostics,
        boolean passed) {
    public EvaluationScenarioResult {
        expectedDocumentIds = List.copyOf(expectedDocumentIds);
        retrievedDocumentIds = List.copyOf(retrievedDocumentIds);
        missingDocumentIds = List.copyOf(missingDocumentIds);
        forbiddenDocumentIds = List.copyOf(forbiddenDocumentIds);
        retrievedVersions = Map.copyOf(retrievedVersions);
    }

    public record GateDiagnostics(int candidateCount, double bestVectorSimilarity, double bestKeywordScore,
                                  double bestExpectedVectorSimilarity, double bestExpectedKeywordScore,
                                  boolean expectedExactMatch, long vectorPassingCandidateCount,
                                  long keywordPassingCandidateCount, int jointPassingCandidateCount,
                                  Map<String, Boolean> ablationDecisions,
                                  double minimumLexicalCoverage, double bestLexicalCoverage,
                                  double bestExpectedLexicalCoverage,
                                  boolean sensitiveRequest, boolean intentPolicyRejection,
                                  boolean roleScopeRejected,
                                  PrefixShadowDiagnostics prefixShadow) {
        public GateDiagnostics {
            ablationDecisions = Map.copyOf(ablationDecisions);
            if (prefixShadow == null) {
                prefixShadow = PrefixShadowDiagnostics.empty();
            }
        }

        public record PrefixShadowDiagnostics(List<String> retrievedDocumentIds, double recallAt5,
                                              double reciprocalRank, boolean vectorGatePassed,
                                              double bestExpectedCosine) {
            public PrefixShadowDiagnostics {
                retrievedDocumentIds = List.copyOf(retrievedDocumentIds);
            }

            public static PrefixShadowDiagnostics empty() {
                return new PrefixShadowDiagnostics(List.of(), 0.0, 0.0, false, 0.0);
            }
        }
    }
}
