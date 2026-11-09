package com.acme.rag.retrieval;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class HybridRetrievalRepository {
    private static final Pattern CJK_RUN = Pattern.compile("[\\u3040-\\u30ff\\u3400-\\u9fff\\uac00-\\ud7af]+");
    private static final Pattern LATIN_TERM = Pattern.compile("[a-z0-9]{3,}");
    private static final String AUTHORIZED_FROM = """
            FROM document_chunks c
            JOIN document_versions v ON v.id = c.document_version_id
            JOIN documents d ON d.id = v.document_id
            WHERE d.status = 'ACTIVE'
              AND d.current_version_id = v.id
              AND v.index_status = 'ACTIVE'
              AND EXISTS (
                  SELECT 1 FROM document_permissions p
                  WHERE p.document_id = d.id AND p.allowed_role IN (:roles)
              )
            """;
    private final JdbcClient jdbc;

    public HybridRetrievalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<RetrievalCandidate> vector(float[] embedding, Set<String> roles, int depth) {
        String vector = vectorLiteral(embedding);
        return jdbc.sql("""
                SELECT c.id, v.id AS version_id, d.id AS document_id, d.title, v.version_number,
                       c.heading_path, c.chunk_text, c.embedding <=> CAST(:embedding AS vector) AS distance
                """ + AUTHORIZED_FROM + " ORDER BY distance ASC, c.id ASC LIMIT :depth")
                .param("embedding", vector).param("roles", roles).param("depth", depth)
                .query((rs, row) -> candidate(rs.getString("id"), rs.getObject("version_id", UUID.class),
                        rs.getString("document_id"), rs.getString("title"), rs.getInt("version_number"),
                        rs.getString("heading_path"), rs.getString("chunk_text"), 1.0 - rs.getDouble("distance"), true, false, false, 0.0))
                .list();
    }

    public List<RetrievalCandidate> keyword(String query, Set<String> roles, int depth) {
        String normalizedQuery = normalizeRollbackPhrase(query);
        List<String> terms = lexicalTerms(normalizedQuery);
        if (terms.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                WITH weighted_query_terms AS (
                    SELECT requested.term,
                           1.0 / ln(2.0 + coalesce(indexed_term.document_frequency, 0)) AS weight
                    FROM unnest(CAST(:terms AS text[])) requested(term)
                    LEFT JOIN (
                        SELECT term, count(*) AS document_frequency
                        FROM document_chunks indexed
                        JOIN document_versions indexed_version ON indexed_version.id = indexed.document_version_id
                        JOIN documents indexed_document ON indexed_document.id = indexed_version.document_id
                        CROSS JOIN LATERAL unnest(indexed.lexical_terms) term
                        WHERE indexed_document.status = 'ACTIVE'
                          AND indexed_document.current_version_id = indexed_version.id
                          AND indexed_version.index_status = 'ACTIVE'
                          AND EXISTS (SELECT 1 FROM document_permissions permission
                              WHERE permission.document_id = indexed_document.id
                                AND permission.allowed_role IN (:roles))
                        GROUP BY term
                    ) indexed_term ON indexed_term.term = requested.term
                ), lexical AS (
                    SELECT array_agg(term) AS query_terms, count(*) AS term_count, sum(weight) AS total_weight,
                           tsvector_to_array(to_tsvector('english', :query)) AS english_terms
                    FROM weighted_query_terms
                )
                SELECT c.id, v.id AS version_id, d.id AS document_id, d.title, v.version_number,
                         c.heading_path, c.chunk_text,
                         greatest(coalesce((SELECT sum(weight) FROM weighted_query_terms
                                   WHERE term = ANY(c.lexical_terms)), 0.0)
                             / nullif(lexical.total_weight, 0),
                             coalesce(ts_rank_cd(c.search_vector, to_tsquery('english', nullif(:latinQuery, ''))), 0.0)) AS rank,
                          greatest(
                              coalesce((SELECT count(*) FROM weighted_query_terms query_term
                                        WHERE query_term.term = ANY(c.lexical_terms))::double precision
                                        / nullif(lexical.term_count, 0), 0.0),
                              coalesce((SELECT count(*) FROM unnest(lexical.english_terms) english_term(term)
                                        WHERE c.search_vector @@ plainto_tsquery('english', english_term.term))::double precision
                                        / nullif(cardinality(lexical.english_terms), 0), 0.0)
                          ) AS lexical_coverage
                """ + KEYWORD_AUTHORIZED_FROM
                + " AND (c.lexical_terms && lexical.query_terms OR "
                + "(nullif(:latinQuery, '') IS NOT NULL AND c.search_vector @@ to_tsquery('english', nullif(:latinQuery, '')))) "
                + "ORDER BY rank DESC, c.id ASC LIMIT :depth")
                .param("terms", textArrayLiteral(terms)).param("latinQuery", latinQuery(terms))
                .param("query", normalizedQuery)
                .param("roles", roles).param("depth", depth)
                .query((rs, row) -> candidate(rs.getString("id"), rs.getObject("version_id", UUID.class),
                        rs.getString("document_id"), rs.getString("title"), rs.getInt("version_number"),
                        rs.getString("heading_path"), rs.getString("chunk_text"), rs.getDouble("rank"), false, true,
                        false, rs.getDouble("lexical_coverage")))
                .list();
    }

    private static String normalizeRollbackPhrase(String query) {
        return query.replaceAll("(?i)\\broll(?:ed)?\\s+back\\b", "rollback");
    }

    private static List<String> lexicalTerms(String query) {
        String normalized = Normalizer.normalize(query, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        Matcher cjk = CJK_RUN.matcher(normalized);
        StringBuffer latinText = new StringBuffer();
        while (cjk.find()) {
            cjk.appendReplacement(latinText, " ");
        }
        cjk.appendTail(latinText);
        Matcher latin = LATIN_TERM.matcher(latinText);
        while (latin.find()) {
            terms.add(latin.group());
        }
        cjk.reset();
        while (cjk.find()) {
            String run = cjk.group();
            if (run.length() == 1) {
                terms.add(run);
            } else {
                for (int index = 0; index < run.length() - 1; index++) {
                    terms.add(run.substring(index, index + 2));
                }
            }
        }
        return new ArrayList<>(terms);
    }

    private static String textArrayLiteral(List<String> terms) {
        return "{" + terms.stream().map(term -> "\"" + term.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"").collect(java.util.stream.Collectors.joining(",")) + "}";
    }

    private static String latinQuery(List<String> terms) {
        return terms.stream().filter(term -> LATIN_TERM.matcher(term).matches())
                .collect(java.util.stream.Collectors.joining(" | "));
    }

    private static final String KEYWORD_AUTHORIZED_FROM = """
            FROM document_chunks c
            CROSS JOIN lexical
            JOIN document_versions v ON v.id = c.document_version_id
            JOIN documents d ON d.id = v.document_id
            WHERE d.status = 'ACTIVE'
              AND d.current_version_id = v.id
              AND v.index_status = 'ACTIVE'
              AND EXISTS (SELECT 1 FROM document_permissions p
                  WHERE p.document_id = d.id AND p.allowed_role IN (:roles))
            """;

    public List<RetrievalCandidate> exact(String identifier, Set<String> roles, int depth) {
        return jdbc.sql("""
                SELECT c.id, v.id AS version_id, d.id AS document_id, d.title, v.version_number,
                       c.heading_path, c.chunk_text, 1.0 AS rank
                """ + AUTHORIZED_FROM + " AND (lower(:identifier) = ANY(c.technical_identifiers) OR lower(:identifier) = ANY("
                + "ARRAY(SELECT rtrim(token, '.') FROM unnest(regexp_split_to_array(lower(c.chunk_text), '[^[:alnum:]_./:-]+')) token))) "
                + "ORDER BY c.id ASC LIMIT :depth")
                .param("identifier", identifier).param("roles", roles).param("depth", depth)
                .query((rs, row) -> candidate(rs.getString("id"), rs.getObject("version_id", UUID.class),
                        rs.getString("document_id"), rs.getString("title"), rs.getInt("version_number"),
                        rs.getString("heading_path"), rs.getString("chunk_text"), rs.getDouble("rank"), false, false, true, 0.0))
                .list();
    }

    public List<RetrievalCandidate> authorizedChunks(Set<String> roles) {
        return jdbc.sql("""
                SELECT c.id, v.id AS version_id, d.id AS document_id, d.title, v.version_number,
                       c.heading_path, c.chunk_text
                """ + AUTHORIZED_FROM + " ORDER BY d.id, c.chunk_index, c.id")
                .param("roles", roles)
                .query((rs, row) -> candidate(rs.getString("id"), rs.getObject("version_id", UUID.class),
                        rs.getString("document_id"), rs.getString("title"), rs.getInt("version_number"),
                        rs.getString("heading_path"), rs.getString("chunk_text"), 0.0, false, false, false, 0.0))
                .list();
    }

    private static RetrievalCandidate candidate(String chunkId, UUID versionId, String documentId, String title,
                                                int version, String section, String text, double score,
                                                boolean vectorMatch, boolean keywordMatch, boolean exactMatch,
                                                double lexicalCoverage) {
        return new RetrievalCandidate(chunkId, versionId, documentId, title, version, section, text, score,
                vectorMatch, keywordMatch, exactMatch, lexicalCoverage);
    }

    private static String vectorLiteral(float[] embedding) {
        StringBuilder literal = new StringBuilder("[");
        for (int index = 0; index < embedding.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(Float.toString(embedding[index]));
        }
        return literal.append(']').toString();
    }
}
