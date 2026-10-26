package com.acme.rag.evaluation;

import java.util.Map;

public record EvaluationMetrics(
        double recallAt5,
        double meanReciprocalRank,
        int aclLeakageCount,
        double freshnessAccuracy,
        double citationIdValidationAccuracy,
        double evidenceGateAccuracy,
        Map<String, GateAblationMetrics> gateAblationMetrics,
        PrefixShadowMetrics prefixShadowMetrics) {
    public EvaluationMetrics {
        gateAblationMetrics = Map.copyOf(gateAblationMetrics);
    }

    public EvaluationMetrics(double recallAt5, double meanReciprocalRank, int aclLeakageCount,
                             double freshnessAccuracy, double citationIdValidationAccuracy,
                             double evidenceGateAccuracy) {
        this(recallAt5, meanReciprocalRank, aclLeakageCount, freshnessAccuracy,
                citationIdValidationAccuracy, evidenceGateAccuracy, Map.of(), PrefixShadowMetrics.empty());
    }

    public EvaluationMetrics(double recallAt5, double meanReciprocalRank, int aclLeakageCount,
                             double freshnessAccuracy, double citationIdValidationAccuracy,
                             double evidenceGateAccuracy, Map<String, GateAblationMetrics> gateAblationMetrics) {
        this(recallAt5, meanReciprocalRank, aclLeakageCount, freshnessAccuracy,
                citationIdValidationAccuracy, evidenceGateAccuracy, gateAblationMetrics, PrefixShadowMetrics.empty());
    }

    public record GateAblationMetrics(Integer truePass, Integer falseAbstention, Integer falseAdmission,
                                      Integer trueAbstention, Double accuracy, Double falseAbstentionRate,
                                      Double falseAdmissionRate) {
        public boolean confusionAvailable() {
            return falseAbstention != null && falseAdmission != null;
        }
    }

    public record PrefixShadowMetrics(boolean available, double recallAt5, double meanReciprocalRank, double vectorGateAccuracy,
                                      int falseAbstentionCount, int falseAdmissionCount) {
        public static PrefixShadowMetrics empty() {
            return new PrefixShadowMetrics(false, 0.0, 0.0, 0.0, 0, 0);
        }
    }
}
