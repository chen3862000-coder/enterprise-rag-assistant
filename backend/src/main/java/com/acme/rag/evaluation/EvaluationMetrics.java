package com.acme.rag.evaluation;

public record EvaluationMetrics(
        double recallAt5,
        double meanReciprocalRank,
        int aclLeakageCount,
        double freshnessAccuracy,
        double citationIdValidationAccuracy,
        double evidenceGateAccuracy) {}
