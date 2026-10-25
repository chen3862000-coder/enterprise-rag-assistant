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
        Boolean shouldAbstain,
        Boolean shouldNotAbstain) {
    public EvaluationScenario {
        expectedDocumentIds = List.copyOf(expectedDocumentIds);
        mustNotRetrieveDocumentIds = List.copyOf(mustNotRetrieveDocumentIds);
    }

    public boolean abstentionExpected() {
        return Boolean.TRUE.equals(shouldAbstain) || expectedDocumentIds.isEmpty() && !Boolean.TRUE.equals(shouldNotAbstain);
    }
}
