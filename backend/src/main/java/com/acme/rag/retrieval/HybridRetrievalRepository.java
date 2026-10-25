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
                        rs.getString("heading_path"), rs.getString("chunk_text"), 1.0 - rs.getDouble("distance")))
                .list();
    }

    public List<RetrievalCandidate> keyword(String query, Set<String> roles, int depth) {
        return jdbc.sql("""
                SELECT c.id, v.id AS version_id, d.id AS document_id, d.title, v.version_number,
                       c.heading_path, c.chunk_text,
                       ts_rank_cd(c.search_vector, plainto_tsquery('english', :query)) AS rank
                """ + AUTHORIZED_FROM + " AND c.search_vector @@ plainto_tsquery('english', :query) "
                + "ORDER BY rank DESC, c.id ASC LIMIT :depth")
                .param("query", query).param("roles", roles).param("depth", depth)
                .query((rs, row) -> candidate(rs.getString("id"), rs.getObject("version_id", UUID.class),
                        rs.getString("document_id"), rs.getString("title"), rs.getInt("version_number"),
                        rs.getString("heading_path"), rs.getString("chunk_text"), rs.getDouble("rank")))
                .list();
    }

    public List<RetrievalCandidate> exact(String identifier, Set<String> roles, int depth) {
        return jdbc.sql("""
                SELECT c.id, v.id AS version_id, d.id AS document_id, d.title, v.version_number,
                       c.heading_path, c.chunk_text, 1.0 AS rank
                """ + AUTHORIZED_FROM + " AND position(lower(:identifier) in lower(c.chunk_text)) > 0 "
                + "ORDER BY c.id ASC LIMIT :depth")
                .param("identifier", identifier).param("roles", roles).param("depth", depth)
                .query((rs, row) -> candidate(rs.getString("id"), rs.getObject("version_id", UUID.class),
                        rs.getString("document_id"), rs.getString("title"), rs.getInt("version_number"),
                        rs.getString("heading_path"), rs.getString("chunk_text"), rs.getDouble("rank")))
                .list();
    }

    private static RetrievalCandidate candidate(String chunkId, UUID versionId, String documentId, String title,
                                                int version, String section, String text, double score) {
        return new RetrievalCandidate(chunkId, versionId, documentId, title, version, section, text, score, 0, 0, 0);
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
