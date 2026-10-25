package com.acme.rag.retrieval;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class EvidenceSufficiencyGate {
    private static final Pattern TECHNICAL_ID = Pattern.compile("(?U)(?=[\\p{Alnum}_./:-]*[0-9])[\\p{Alnum}_./:-]{2,}");
    private static final Pattern NUMBERED_HTTP_STATUS = Pattern.compile("(?i)\\b(?:http|status|error|code)\\s*[-:]?\\s*[0-9]{3}\\b");
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
        if (chunks.isEmpty()) {
            return new Decision(false, "NO_AUTHORIZED_CANDIDATES", List.of());
        }
        List<RankedChunk> exactMatches = exactIdentifierMatches(query, chunks);
        if (!exactMatches.isEmpty()) {
            return new Decision(true, "EXACT_IDENTIFIER_MATCH", exactMatches);
        }
        List<RankedChunk> strong = chunks.stream()
                .filter(chunk -> chunk.vectorSimilarity() >= minimumVectorSimilarity
                        && chunk.keywordScore() >= minimumKeywordScore)
                .toList();
        if (strong.isEmpty()) {
            return new Decision(false, "NO_STRONG_EVIDENCE", List.of());
        }
        return new Decision(true, "LEXICAL_AND_RELEVANCE_MATCH", strong);
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

    public record Decision(boolean sufficient, String reason, List<RankedChunk> chunks) {
        public Decision {
            chunks = List.copyOf(chunks);
        }
    }
}
