package com.acme.rag.retrieval;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class EvidenceSufficiencyGate {
    private static final Pattern TECHNICAL_ID = Pattern.compile("(?U)(?=[\\p{Alnum}_./:-]*[0-9])[\\p{Alnum}_./:-]{2,}");
    private static final Pattern NUMBERED_HTTP_STATUS = Pattern.compile("(?i)\\b(?:http|status|error|code)\\s*[-:]?\\s*[0-9]{3}\\b");
    public static final double RELAXED_VECTOR_SIMILARITY = 0.25;
    public static final double RELAXED_KEYWORD_SCORE = 0.0001;
    private static final java.util.Set<String> SENSITIVE_TERMS = java.util.Set.of(
            "acquisition", "price", "salary", "credential", "credentials", "secret", "secrets",
            "password", "passwords", "private", "token", "tokens", "key");
    private final double minimumVectorSimilarity;
    private final double minimumKeywordScore;

    public EvidenceSufficiencyGate(
            @Value("${app.retrieval.minimum-vector-similarity:0.35}") double minimumVectorSimilarity,
            @Value("${app.retrieval.minimum-keyword-score:0.0005}") double minimumKeywordScore) {
        this.minimumVectorSimilarity = minimumVectorSimilarity;
        this.minimumKeywordScore = minimumKeywordScore;
    }

    public Decision evaluate(String query, List<RankedChunk> chunks) {
        return evaluate(query, chunks, Set.of());
    }

    public Decision evaluate(String query, List<RankedChunk> chunks, Set<String> expectedDocumentIds) {
        if (chunks.isEmpty()) {
            return new Decision(false, "NO_AUTHORIZED_CANDIDATES", List.of(),
                    diagnostics(chunks, expectedDocumentIds, List.of(), false));
        }
        List<RankedChunk> exactMatches = exactIdentifierMatches(query, chunks);
        boolean exactAllowed = !exactMatches.isEmpty();
        List<RankedChunk> vectorMatches = chunks.stream()
                .filter(chunk -> chunk.vectorSimilarity() >= minimumVectorSimilarity).toList();
        List<RankedChunk> keywordMatches = chunks.stream()
                .filter(chunk -> chunk.keywordScore() >= minimumKeywordScore).toList();
        List<RankedChunk> jointMatches = chunks.stream()
                .filter(chunk -> chunk.vectorSimilarity() >= minimumVectorSimilarity
                        && chunk.keywordScore() >= minimumKeywordScore).toList();
        Diagnostic diagnostic = diagnostics(chunks, expectedDocumentIds, jointMatches, exactAllowed);
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
                                   List<RankedChunk> jointMatches, boolean exactAllowed) {
        List<RankedChunk> expected = chunks.stream()
                .filter(chunk -> expectedDocumentIds.contains(chunk.documentId())).toList();
        Map<String, Boolean> ablations = Map.of(
                "CURRENT_AND", exactAllowed || !jointMatches.isEmpty(),
                "VECTOR_ONLY", exactAllowed || chunks.stream()
                        .anyMatch(chunk -> chunk.vectorSimilarity() >= minimumVectorSimilarity),
                "KEYWORD_ONLY", exactAllowed || chunks.stream()
                        .anyMatch(chunk -> chunk.keywordScore() >= minimumKeywordScore),
                "RELAXED_AND", exactAllowed || chunks.stream().anyMatch(chunk ->
                        chunk.vectorSimilarity() >= RELAXED_VECTOR_SIMILARITY
                                && chunk.keywordScore() >= RELAXED_KEYWORD_SCORE));
        return new Diagnostic(chunks.size(), maximum(chunks, RankedChunk::vectorSimilarity),
                maximum(chunks, RankedChunk::keywordScore), maximum(expected, RankedChunk::vectorSimilarity),
                maximum(expected, RankedChunk::keywordScore), expected.stream().anyMatch(RankedChunk::exactMatch),
                chunks.stream().filter(chunk -> chunk.vectorSimilarity() >= minimumVectorSimilarity).count(),
                chunks.stream().filter(chunk -> chunk.keywordScore() >= minimumKeywordScore).count(),
                jointMatches.size(), ablations);
    }

    private static double maximum(List<RankedChunk> chunks, java.util.function.ToDoubleFunction<RankedChunk> value) {
        return chunks.stream().mapToDouble(value).max().orElse(0.0);
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

    private static List<RankedChunk> exactIdentifierMatches(String query, List<RankedChunk> chunks) {
        if (queryTerms(query).stream().anyMatch(SENSITIVE_TERMS::contains)) {
            return List.of();
        }
        java.util.Set<String> identifiers = new java.util.HashSet<>();
        var matcher = TECHNICAL_ID.matcher(query);
        while (matcher.find()) {
            String identifier = matcher.group();
            boolean hasLetter = identifier.codePoints().anyMatch(Character::isLetter);
            if (hasLetter || isNamedNumericCode(query, identifier)) {
                identifiers.add(identifier.toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (identifiers.size() != 1) {
            return List.of();
        }
        return chunks.stream().filter(RankedChunk::exactMatch)
                .filter(chunk -> identifiers.stream().anyMatch(identifier -> containsIgnoreCase(chunk.text(), identifier)))
                .toList();
    }

    private static java.util.Set<String> queryTerms(String query) {
        return java.util.Arrays.stream(query.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+"))
                .filter(term -> term.length() > 2).collect(java.util.stream.Collectors.toSet());
    }

    private static boolean containsIgnoreCase(String text, String value) {
        return text.toLowerCase(java.util.Locale.ROOT).contains(value.toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean isNamedNumericCode(String query, String identifier) {
        return !identifier.codePoints().anyMatch(Character::isLetter)
                && NUMBERED_HTTP_STATUS.matcher(query).find();
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
                             Map<String, Boolean> ablationDecisions) {
        public Diagnostic {
            ablationDecisions = Map.copyOf(ablationDecisions);
        }

        public static Diagnostic empty(int candidateCount) {
            return new Diagnostic(candidateCount, 0.0, 0.0, 0.0, 0.0, false, 0, 0, 0, Map.of());
        }
    }
}
