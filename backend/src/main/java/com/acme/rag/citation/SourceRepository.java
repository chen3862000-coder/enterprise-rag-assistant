package com.acme.rag.citation;

import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SourceRepository {
    private final JdbcClient jdbc;

    public SourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SourcePreview> findAuthorized(String chunkId, Set<String> roles) {
        return jdbc.sql("""
                SELECT c.id AS chunk_id, d.id AS document_id, d.title, v.version_number, c.heading_path, c.chunk_text
                FROM document_chunks c
                JOIN document_versions v ON v.id = c.document_version_id
                JOIN documents d ON d.id = v.document_id
                WHERE c.id = :chunkId
                  AND d.status = 'ACTIVE'
                  AND d.current_version_id = v.id
                  AND v.index_status = 'ACTIVE'
                  AND EXISTS (
                      SELECT 1 FROM document_permissions p
                      WHERE p.document_id = d.id AND p.allowed_role IN (:roles)
                  )
                """)
                .param("chunkId", chunkId).param("roles", roles)
                .query((rs, row) -> new SourcePreview(rs.getString("chunk_id"), rs.getString("document_id"),
                        rs.getString("title"), rs.getInt("version_number"), rs.getString("heading_path"),
                        rs.getString("chunk_text"))).optional();
    }

    public Optional<SourcePreview> findAuthorized(String chunkId, String documentId, Set<String> roles) {
        return jdbc.sql("""
                SELECT c.id AS chunk_id, d.id AS document_id, d.title, v.version_number, c.heading_path, c.chunk_text
                FROM document_chunks c
                JOIN document_versions v ON v.id = c.document_version_id
                JOIN documents d ON d.id = v.document_id
                WHERE c.id = :chunkId AND d.id = :documentId
                  AND d.status = 'ACTIVE' AND d.current_version_id = v.id AND v.index_status = 'ACTIVE'
                  AND EXISTS (SELECT 1 FROM document_permissions p
                      WHERE p.document_id = d.id AND p.allowed_role IN (:roles))
                """)
                .param("chunkId", chunkId).param("documentId", documentId).param("roles", roles)
                .query((rs, row) -> new SourcePreview(rs.getString("chunk_id"), rs.getString("document_id"),
                        rs.getString("title"), rs.getInt("version_number"), rs.getString("heading_path"),
                        rs.getString("chunk_text"))).optional();
    }
}
