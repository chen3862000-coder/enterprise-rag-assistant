package com.acme.rag.evaluation;

import java.util.List;

public record EvaluationScenario(
        String id,
        EvaluationCategory category,
        String user,
        String query,
        List<String> expectedDocumentIds,
        List<String> mustNotRetrieveDocumentIds,
        Integer expectedVersion,
        Boolean shouldPassEvidenceGate,
        Boolean shouldAbstain,
        Boolean shouldNotAbstain) {
    public EvaluationScenario {
        expectedDocumentIds = List.copyOf(expectedDocumentIds);
        mustNotRetrieveDocumentIds = List.copyOf(mustNotRetrieveDocumentIds);
    }

    public boolean abstentionExpected() {
        return Boolean.TRUE.equals(shouldAbstain);
    }

    public boolean evidenceGateExpected() {
        if (shouldPassEvidenceGate != null) {
            return shouldPassEvidenceGate;
        }
        if (Boolean.TRUE.equals(shouldAbstain)) {
            return false;
        }
        return Boolean.TRUE.equals(shouldNotAbstain) || !expectedDocumentIds.isEmpty();
    }
}
