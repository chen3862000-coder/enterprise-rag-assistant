package com.acme.rag.retrieval;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deterministically admits only sufficiently supported candidates and fails closed on intent/scope rejection. */
@Component
public final class EvidenceSufficiencyGate {
    private static final java.util.regex.Pattern CJK_RUN = java.util.regex.Pattern.compile(
            "[\\u3040-\\u30ff\\u3400-\\u9fff\\uac00-\\ud7af]+");
    public static final double RELAXED_VECTOR_SIMILARITY = 0.25;
    public static final double RELAXED_KEYWORD_SCORE = 0.1;
    public static final double RELEASE_MAX_FALSE_ABSTENTION_RATE = 0.20;
    public static final double RELEASE_MAX_FALSE_ADMISSION_RATE = 0.05;
    private final double minimumVectorSimilarity;
    private final double minimumKeywordScore;
    private final RetrievalIntentPolicy intentPolicy = new RetrievalIntentPolicy();

    public EvidenceSufficiencyGate(
            @Value("${app.retrieval.minimum-vector-similarity:0.35}") double minimumVectorSimilarity,
            @Value("${app.retrieval.minimum-keyword-score:0.3}") double minimumKeywordScore) {
        this.minimumVectorSimilarity = minimumVectorSimilarity;
        this.minimumKeywordScore = minimumKeywordScore;
    }

    /** Evaluates candidates using the default demo-role scope; runtime retrieval should pass caller roles explicitly. */
    public Decision evaluate(String query, List<RankedChunk> chunks) {
        return evaluate(query, chunks, Set.of(), Set.of("PUBLIC", "ENGINEERING", "HR", "SECURITY_ADMIN"));
    }

    /** Evaluates candidates with expected-document diagnostics and the default demo-role scope. */
    public Decision evaluate(String query, List<RankedChunk> chunks, Set<String> expectedDocumentIds) {
        return evaluate(query, chunks, expectedDocumentIds,
                Set.of("PUBLIC", "ENGINEERING", "HR", "SECURITY_ADMIN"));
    }

    /**
     * Admits exact authorized identifier matches or candidates meeting the configured vector, keyword,
     * and adaptive lexical-coverage thresholds; expected IDs are diagnostic and do not grant access.
     */
    public Decision evaluate(String query, List<RankedChunk> chunks, Set<String> expectedDocumentIds,
                             Set<String> authorizedRoles) {
        return evaluateForPlannedQuery(query, query, chunks, expectedDocumentIds, authorizedRoles);
    }

    /** Uses planner-derived subject terms for coverage while policy and identifier checks remain bound to the original. */
    public Decision evaluateForPlannedQuery(String safetyQuery, String coverageQuery, List<RankedChunk> chunks,
                                            Set<String> expectedDocumentIds, Set<String> authorizedRoles) {
        if (chunks.isEmpty()) {
            return new Decision(false, "NO_AUTHORIZED_CANDIDATES", List.of(),
                    diagnostics(chunks, expectedDocumentIds, List.of(), false,
                            "NO_AUTHORIZED_CANDIDATES", RetrievalIntentPolicy.containsSensitiveTerm(safetyQuery), minimumLexicalCoverage(coverageQuery)));
        }
        List<RankedChunk> exactMatches = intentPolicy.exactIdentifierMatches(safetyQuery, chunks);
        String policyRejection = intentPolicy.rejection(safetyQuery, exactMatches, authorizedRoles);
        boolean exactAllowed = !exactMatches.isEmpty();
        boolean sensitiveRequest = RetrievalIntentPolicy.containsSensitiveTerm(safetyQuery);
        List<RankedChunk> vectorMatches = chunks.stream()
                .filter(chunk -> chunk.vectorSimilarity() >= minimumVectorSimilarity).toList();
        List<RankedChunk> keywordMatches = chunks.stream()
                .filter(chunk -> chunk.keywordScore() >= minimumKeywordScore).toList();
        double requiredCoverage = minimumLexicalCoverage(coverageQuery);
        List<RankedChunk> jointMatches = chunks.stream()
                .filter(chunk -> chunk.vectorSimilarity() >= minimumVectorSimilarity
                        && chunk.keywordScore() >= minimumKeywordScore
                        && chunk.lexicalCoverage() >= requiredCoverage).toList();
        Diagnostic diagnostic = diagnostics(chunks, expectedDocumentIds, jointMatches, exactAllowed,
                policyRejection, sensitiveRequest, requiredCoverage);
        if (policyRejection != null) {
            return new Decision(false, policyRejection, List.of(), diagnostic);
        }
        if (!exactMatches.isEmpty()) {
            return new Decision(true, "EXACT_IDENTIFIER_MATCH", exactMatches, diagnostic);
        }
        if (jointMatches.isEmpty()) {
            String reason = rejectionReason(chunks, vectorMatches, keywordMatches);
            return new Decision(false, reason, List.of(), diagnostic);
        }
        return new Decision(true, "LEXICAL_AND_RELEVANCE_MATCH", jointMatches, diagnostic);
    }

    private Diagnostic diagnostics(List<RankedChunk> chunks, Set<String> expectedDocumentIds,
                                   List<RankedChunk> jointMatches, boolean exactAllowed,
                                   String policyRejection, boolean sensitiveRequest,
                                   double requiredCoverage) {
        List<RankedChunk> expected = chunks.stream()
                .filter(chunk -> expectedDocumentIds.contains(chunk.documentId())).toList();
        Map<String, Boolean> ablations = Map.of(
                "CURRENT_COVERAGE", policyRejection == null && (exactAllowed || !jointMatches.isEmpty()),
                "VECTOR_ONLY", exactAllowed || chunks.stream()
                        .anyMatch(chunk -> chunk.vectorSimilarity() >= minimumVectorSimilarity),
                "LEXICAL_COVERAGE_ONLY", exactAllowed || chunks.stream().anyMatch(chunk ->
                        chunk.keywordScore() >= minimumKeywordScore && chunk.lexicalCoverage() >= requiredCoverage),
                "COVERAGE_030", policyRejection == null && (exactAllowed || chunks.stream().anyMatch(chunk ->
                        chunk.vectorSimilarity() >= minimumVectorSimilarity && chunk.keywordScore() >= minimumKeywordScore
                                && chunk.lexicalCoverage() >= 0.30)),
                "COVERAGE_040", policyRejection == null && (exactAllowed || chunks.stream().anyMatch(chunk ->
                        chunk.vectorSimilarity() >= minimumVectorSimilarity && chunk.keywordScore() >= minimumKeywordScore
                                && chunk.lexicalCoverage() >= 0.40)));
        return new Diagnostic(chunks.size(), maximum(chunks, RankedChunk::vectorSimilarity),
                maximum(chunks, RankedChunk::keywordScore), maximum(expected, RankedChunk::vectorSimilarity),
                maximum(expected, RankedChunk::keywordScore), expected.stream().anyMatch(RankedChunk::exactMatch),
                chunks.stream().filter(chunk -> chunk.vectorSimilarity() >= minimumVectorSimilarity).count(),
                chunks.stream().filter(chunk -> chunk.keywordScore() >= minimumKeywordScore).count(),
                jointMatches.size(), ablations, requiredCoverage, maximum(chunks, RankedChunk::lexicalCoverage),
                maximum(expected, RankedChunk::lexicalCoverage), sensitiveRequest,
                policyRejection != null && !policyRejection.startsWith("REQUIRED_"),
                policyRejection != null && policyRejection.startsWith("REQUIRED_"));
    }

    private static double maximum(List<RankedChunk> chunks, java.util.function.ToDoubleFunction<RankedChunk> value) {
        return chunks.stream().mapToDouble(value).max().orElse(0.0);
    }

    static double minimumLexicalCoverage(String query) {
        String normalized = java.text.Normalizer.normalize(query, java.text.Normalizer.Form.NFKC)
                .toLowerCase(java.util.Locale.ROOT);
        java.util.Set<String> lexicalTerms = new java.util.HashSet<>();
        java.util.regex.Matcher latin = java.util.regex.Pattern.compile("[a-z0-9]{3,}").matcher(normalized);
        while (latin.find()) {
            lexicalTerms.add(latin.group());
        }
        var matcher = CJK_RUN.matcher(normalized);
        java.util.Set<String> bigrams = new java.util.HashSet<>();
        while (matcher.find()) {
            String run = matcher.group();
            if (run.length() == 1) {
                bigrams.add(run);
            } else {
                for (int index = 0; index < run.length() - 1; index++) {
                    bigrams.add(run.substring(index, index + 2));
                }
            }
        }
        int meaningfulTerms = Math.max(lexicalTerms.size(), bigrams.size());
        if (meaningfulTerms <= 3) {
            return 0.30;
        }
        if (meaningfulTerms <= 6) {
            return 0.35;
        }
        return 0.40;
    }

    private String rejectionReason(List<RankedChunk> chunks, List<RankedChunk> vectorMatches,
                                   List<RankedChunk> keywordMatches) {
        if (keywordMatches.isEmpty()) {
            return "NO_KEYWORD_MATCH";
        }
        if (vectorMatches.isEmpty()) {
            return "BELOW_VECTOR_THRESHOLD";
        }
        return chunks.stream().anyMatch(chunk -> chunk.vectorSimilarity() >= minimumVectorSimilarity
                || chunk.keywordScore() >= minimumKeywordScore) ? "SPLIT_SIGNALS_NO_SINGLE_CANDIDATE" : "NO_STRONG_EVIDENCE";
    }

    public record Decision(boolean sufficient, String reason, List<RankedChunk> chunks, Diagnostic diagnostic) {
        public Decision {
            chunks = List.copyOf(chunks);
        }
    }

    public record Diagnostic(int candidateCount, double bestVectorSimilarity, double bestKeywordScore,
                             double bestExpectedVectorSimilarity, double bestExpectedKeywordScore,
                             boolean expectedExactMatch, long vectorPassingCandidateCount,
                             long keywordPassingCandidateCount, int jointPassingCandidateCount,
                             Map<String, Boolean> ablationDecisions, double minimumLexicalCoverage,
                             double bestLexicalCoverage,
                             double bestExpectedLexicalCoverage, boolean sensitiveRequest,
                             boolean intentPolicyRejection, boolean roleScopeRejected) {
        public Diagnostic {
            ablationDecisions = Map.copyOf(ablationDecisions);
        }

        public static Diagnostic empty(int candidateCount) {
            return new Diagnostic(candidateCount, 0.0, 0.0, 0.0, 0.0, false, 0, 0, 0,
                    Map.of(), 0.0, 0.0, 0.0, false, false, false);
        }

        public static boolean releaseThresholdsMet(double falseAbstentionRate, double falseAdmissionRate,
                                                   int aclLeakageCount) {
            return falseAbstentionRate <= RELEASE_MAX_FALSE_ABSTENTION_RATE
                    && falseAdmissionRate <= RELEASE_MAX_FALSE_ADMISSION_RATE && aclLeakageCount == 0;
        }
    }
}
