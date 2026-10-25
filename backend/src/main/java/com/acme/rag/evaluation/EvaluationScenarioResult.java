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
        boolean aclLeakage,
        boolean freshnessCorrect,
        boolean citationIdValidationCorrect,
        boolean evidenceGateCorrect,
        String gateReason,
        boolean passed) {
    public EvaluationScenarioResult {
        expectedDocumentIds = List.copyOf(expectedDocumentIds);
        retrievedDocumentIds = List.copyOf(retrievedDocumentIds);
        missingDocumentIds = List.copyOf(missingDocumentIds);
        forbiddenDocumentIds = List.copyOf(forbiddenDocumentIds);
        retrievedVersions = Map.copyOf(retrievedVersions);
    }
}
