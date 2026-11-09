package com.acme.rag.retrieval;

import java.util.Map;

/** Bounded planner metadata safe for trace persistence; never contains query text. */
public record QueryPlanningDiagnostics(String provider, String model, int latencyMs, String status,
                                       String fallbackReason, QueryIntent intent,
                                       int generatedQueryCount, int deduplicatedQueryCount,
                                       Map<String, Integer> channelCandidateCounts, int candidateCount,
                                       Map<String, Integer> retrievalCandidateCounts) {
    public QueryPlanningDiagnostics {
        channelCandidateCounts = Map.copyOf(channelCandidateCounts);
        retrievalCandidateCounts = Map.copyOf(retrievalCandidateCounts);
    }

    public QueryPlanningDiagnostics(String provider, String model, int latencyMs, String status,
                                    String fallbackReason, QueryIntent intent,
                                    int generatedQueryCount, int deduplicatedQueryCount) {
        this(provider, model, latencyMs, status, fallbackReason, intent, generatedQueryCount,
                deduplicatedQueryCount, Map.of(), 0, Map.of());
    }

    public static QueryPlanningDiagnostics disabled() {
        return new QueryPlanningDiagnostics("deterministic", "query-normalizer-v1", 0,
                "DISABLED", "PLANNER_DISABLED", QueryIntent.UNKNOWN, 0, 0);
    }

    /** Adds bounded candidate counts without persisting any query variant text. */
    public QueryPlanningDiagnostics withRetrieval(Map<String, Integer> channelCounts, int candidates,
                                                  Map<String, Integer> retrievalCounts) {
        return new QueryPlanningDiagnostics(provider, model, latencyMs, status, fallbackReason, intent,
                generatedQueryCount, deduplicatedQueryCount, channelCounts, candidates, retrievalCounts);
    }
}
