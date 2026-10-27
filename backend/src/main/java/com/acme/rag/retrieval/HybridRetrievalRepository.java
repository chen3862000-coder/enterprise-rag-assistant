package com.acme.rag.retrieval;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class HybridRetrievalRepository {
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
        return jdbc.sql("""
                WITH lexical AS (
                    SELECT to_tsquery('english', nullif(array_to_string(
                               tsvector_to_array(to_tsvector('english', :query)), ' | '), '')) AS tsquery,
                           tsvector_to_array(to_tsvector('english', :query)) AS query_terms,
                           cardinality(tsvector_to_array(to_tsvector('english', :query))) AS term_count
                )
                SELECT c.id, v.id AS version_id, d.id AS document_id, d.title, v.version_number,
                        c.heading_path, c.chunk_text,
                        ts_rank_cd(c.search_vector, lexical.tsquery) AS rank,
                        lexical.term_count,
                        (SELECT count(*) FROM unnest(lexical.query_terms) query_term
                         WHERE c.search_vector @@ plainto_tsquery('english', query_term)) AS matched_terms
                """ + KEYWORD_AUTHORIZED_FROM
                + " ORDER BY rank DESC, c.id ASC LIMIT :depth")
                .param("query", query).param("roles", roles).param("depth", depth)
                .query((rs, row) -> candidate(rs.getString("id"), rs.getObject("version_id", UUID.class),
                        rs.getString("document_id"), rs.getString("title"), rs.getInt("version_number"),
                        rs.getString("heading_path"), rs.getString("chunk_text"), rs.getDouble("rank"), false, true,
                        false, coverage(rs.getLong("matched_terms"), rs.getLong("term_count"))))
                .list();
    }

    private static final String KEYWORD_AUTHORIZED_FROM = """
            FROM document_chunks c
            CROSS JOIN lexical
            JOIN document_versions v ON v.id = c.document_version_id
            JOIN documents d ON d.id = v.document_id
            WHERE d.status = 'ACTIVE'
              AND d.current_version_id = v.id
              AND v.index_status = 'ACTIVE'
              AND c.search_vector @@ lexical.tsquery
              AND EXISTS (SELECT 1 FROM document_permissions p
                  WHERE p.document_id = d.id AND p.allowed_role IN (:roles))
            """;

    public List<RetrievalCandidate> exact(String identifier, Set<String> roles, int depth) {
        return jdbc.sql("""
                SELECT c.id, v.id AS version_id, d.id AS document_id, d.title, v.version_number,
                       c.heading_path, c.chunk_text, 1.0 AS rank
                """ + AUTHORIZED_FROM + " AND lower(:identifier) = ANY("
                + "regexp_split_to_array(lower(c.chunk_text), '[^[:alnum:]_./:-]+')) "
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

    private static double coverage(long matched, long total) {
        return total == 0 ? 0.0 : (double) matched / total;
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
