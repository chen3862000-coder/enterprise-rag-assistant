package com.acme.rag.document.ingestion;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.JdbcTemplate;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentManagementRepository {
    private static final String DOCUMENT_LIST_SELECT = """
            SELECT DISTINCT d.id, d.title, d.department, d.classification, d.status,
                v.version_number, v.index_status, v.created_at
            FROM documents d
            LEFT JOIN document_versions v ON v.id = d.current_version_id
            WHERE
            """;
    private static final String VERSION_LIST_SELECT = """
            SELECT v.id, v.version_number, v.content_hash, v.source_filename, v.effective_at,
                v.index_status, v.embedding_model_id, v.embedding_dimensions, v.created_at,
                (d.current_version_id = v.id) AS active
            FROM document_versions v
            JOIN documents d ON d.id = v.document_id
            WHERE d.id = :documentId AND
            """;
    private final JdbcClient jdbc;
    private final JdbcTemplate updates;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring JdbcTemplate is a private data-access collaborator and is not exposed")
    public DocumentManagementRepository(JdbcClient jdbc, JdbcTemplate updates) {
        this.jdbc = jdbc;
        this.updates = updates;
    }

    public List<DocumentSummary> list(Set<String> roles) {
        return listWhere("EXISTS (SELECT 1 FROM document_permissions p WHERE p.document_id = d.id AND p.allowed_role IN (:roles))", roles);
    }

    public List<DocumentSummary> listAllForAdmin() {
        return listWhere("TRUE", Set.of("__admin__"));
    }

    private List<DocumentSummary> listWhere(String permissionPredicate, Set<String> roles) {
        return jdbc.sql(DOCUMENT_LIST_SELECT + permissionPredicate + " ORDER BY d.title, d.id")
                .param("roles", roles)
                .query((rs, row) -> new DocumentSummary(rs.getString("id"), rs.getString("title"),
                        rs.getString("department"), rs.getString("classification"), rs.getString("status"),
                        (Integer) rs.getObject("version_number"), rs.getString("index_status"),
                        rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public List<DocumentVersionSummary> versions(String documentId, Set<String> roles) {
        return versionsWhere(documentId, "EXISTS (SELECT 1 FROM document_permissions p WHERE p.document_id = d.id AND p.allowed_role IN (:roles))", roles);
    }

    public List<DocumentVersionSummary> versionsAllForAdmin(String documentId) {
        return versionsWhere(documentId, "TRUE", Set.of("__admin__"));
    }

    private List<DocumentVersionSummary> versionsWhere(String documentId, String permissionPredicate, Set<String> roles) {
        return jdbc.sql(VERSION_LIST_SELECT + permissionPredicate + " ORDER BY v.version_number DESC")
                .param("documentId", documentId).param("roles", roles)
                .query((rs, row) -> new DocumentVersionSummary(rs.getObject("id", UUID.class),
                        rs.getInt("version_number"), rs.getString("content_hash"), rs.getString("source_filename"),
                        rs.getTimestamp("effective_at") == null ? null : rs.getTimestamp("effective_at").toInstant(),
                        rs.getString("index_status"), rs.getString("embedding_model_id"),
                        rs.getInt("embedding_dimensions"), rs.getTimestamp("created_at").toInstant(), rs.getBoolean("active")))
                .list();
    }

    public boolean disable(String documentId) {
        return updates.update("UPDATE documents SET status = 'DISABLED', updated_at = now() WHERE id = ? AND status = 'ACTIVE'", documentId) == 1;
    }

    public boolean delete(String documentId) {
        return updates.update("UPDATE documents SET status = 'DELETED', updated_at = now() WHERE id = ? AND status <> 'DELETED'",
                documentId) == 1;
    }
}
