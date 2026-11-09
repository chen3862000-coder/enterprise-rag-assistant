package com.acme.rag.retrieval;

import java.util.List;

/** Validated retrieval formulations. It deliberately has no identity, ACL, or filter fields. */
public record QueryPlan(QueryIntent intent, String subject, List<String> constraints,
                        List<String> lexicalQueries, String semanticQuery,
                        List<String> alternativeQueries, String language) {
    public QueryPlan {
        if (intent == null || subject == null || subject.isBlank() || subject.length() > 300
                || semanticQuery == null || semanticQuery.isBlank() || semanticQuery.length() > 500
                || language == null || language.length() < 2 || language.length() > 16) {
            throw new IllegalArgumentException("Query plan contains invalid scalar fields");
        }
        subject = subject.strip();
        semanticQuery = semanticQuery.strip();
        language = language.strip();
        constraints = List.copyOf(validateList(constraints, 8));
        lexicalQueries = List.copyOf(validateList(lexicalQueries, 4));
        alternativeQueries = List.copyOf(validateList(alternativeQueries, 3));
    }

    private static List<String> validateList(List<String> values, int maximum) {
        if (values == null || values.size() > maximum) {
            throw new IllegalArgumentException("Query plan contains too many variants");
        }
        List<String> validated = values.stream().map(value -> {
            if (value == null || value.isBlank() || value.length() > 500) {
                throw new IllegalArgumentException("Query plan contains an invalid query variant");
            }
            return value.strip();
        }).distinct().toList();
        return validated;
    }
}
