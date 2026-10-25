package com.acme.rag.document.ingestion;

import com.acme.rag.document.chunking.DocumentChunk;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
class DocumentVersionRepository {
    private static final String STATUS_INDEXING = "INDEXING";
    private static final String STATUS_FAILED = "FAILED";

    private final JdbcTemplate jdbc;
    private final JdbcClient client;
    private final TransactionTemplate transactions;
    private final String modelId;
    private final int dimensions;

    DocumentVersionRepository(
            JdbcTemplate jdbc,
            JdbcClient client,
            TransactionTemplate transactions,
            @Value("${app.ingestion.embedding-model-id:nomic-embed-text}") String modelId,
            @Value("${app.ingestion.embedding-dimensions:768}") int dimensions) {
        this.jdbc = jdbc;
        this.client = client;
        this.transactions = transactions;
        this.modelId = modelId;
        this.dimensions = dimensions;
    }

    VersionReservation reserve(ValidatedUpload upload, String contentHash) {
        UUID versionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        return transactions.execute(status -> reserveLocked(upload, contentHash, versionId, jobId));
    }

    int countChunks(UUID versionId) {
        return client.sql("SELECT count(*) FROM document_chunks WHERE document_version_id = :id")
                .param("id", versionId).query(Integer.class).single();
    }

    boolean isActiveContent(String documentId, String contentHash) {
        return Boolean.TRUE.equals(client.sql("""
                SELECT EXISTS (SELECT 1 FROM documents d JOIN document_versions v ON v.id = d.current_version_id
                    WHERE d.id = :documentId AND d.status = 'ACTIVE' AND v.content_hash = :contentHash)
                """).param("documentId", documentId).param("contentHash", contentHash)
                .query(Boolean.class).single());
    }

    void activate(String documentId, Set<String> roles, VersionReservation reservation,
                  List<DocumentChunk> chunks, List<float[]> vectors) {
        transactions.executeWithoutResult(status -> {
            lockDocument(documentId);
            updateMetadata(documentId, reservation.metadata());
            persistChunks(documentId, reservation, chunks, vectors);
            jdbc.update("UPDATE document_versions SET index_status = 'SUPERSEDED' WHERE document_id = ? AND index_status = 'ACTIVE'", documentId);
            jdbc.update("UPDATE document_versions SET index_status = 'ACTIVE' WHERE id = ? AND index_status = 'INDEXING'", reservation.versionId());
            jdbc.update("UPDATE documents SET current_version_id = ?, status = 'ACTIVE', updated_at = now() WHERE id = ?",
                    reservation.versionId(), documentId);
            replacePermissions(documentId, roles);
            jdbc.update("UPDATE ingestion_jobs SET status = 'SUCCEEDED', completed_at = now() WHERE id = ?", reservation.jobId());
        });
    }

    private void replacePermissions(String documentId, Set<String> roles) {
        jdbc.update("DELETE FROM document_permissions WHERE document_id = ?", documentId);
        roles.forEach(role -> jdbc.update(
                "INSERT INTO document_permissions (document_id, allowed_role) VALUES (?, ?)", documentId, role));
    }

    private void updateMetadata(String documentId, DocumentMetadata metadata) {
        jdbc.update("UPDATE documents SET title = ?, department = ?, classification = ?, updated_at = now() WHERE id = ?",
                metadata.title(), metadata.department(), metadata.classification(), documentId);
    }

    void fail(String documentId, VersionReservation reservation, RuntimeException failure) {
        transactions.executeWithoutResult(status -> {
            String message = failure.getMessage() == null ? "Indexing failed" : failure.getMessage();
            String safeMessage = message.substring(0, Math.min(message.length(), 500));
            jdbc.update("UPDATE document_versions SET index_status = 'FAILED' WHERE id = ? AND index_status = 'INDEXING'",
                    reservation.versionId());
            jdbc.update("UPDATE ingestion_jobs SET status = 'FAILED', error_message = ?, completed_at = now() WHERE id = ?",
                    safeMessage, reservation.jobId());
            jdbc.update("UPDATE documents SET status = 'DISABLED' WHERE id = ? AND current_version_id IS NULL", documentId);
        });
    }

    private VersionReservation reserveLocked(ValidatedUpload upload, String contentHash, UUID versionId, UUID jobId) {
        Optional<String> document = client.sql("SELECT id FROM documents WHERE id = :id FOR UPDATE")
                .param("id", upload.documentId()).query(String.class).optional();
        if (document.isEmpty()) {
            createDocument(upload);
        }
        Optional<ExistingVersion> existing = findReusableVersion(upload.documentId(), contentHash);
        if (existing.isPresent()) {
            return reuseOrRetry(existing.get(), upload, jobId);
        }
        return createVersion(upload, contentHash, versionId, jobId);
    }

    private void createDocument(ValidatedUpload upload) {
        jdbc.update("INSERT INTO documents (id, title, department, classification) VALUES (?, ?, ?, ?)",
                upload.documentId(), upload.title(), upload.department(), upload.classification());
        upload.roles().forEach(role -> jdbc.update(
                "INSERT INTO document_permissions (document_id, allowed_role) VALUES (?, ?)", upload.documentId(), role));
    }

    private Optional<ExistingVersion> findReusableVersion(String documentId, String contentHash) {
        return client.sql("""
                SELECT v.id, v.version_number, v.index_status
                FROM document_versions v
                LEFT JOIN documents d ON d.id = v.document_id
                WHERE v.document_id = :documentId AND v.content_hash = :hash
                  AND (v.index_status IN ('INDEXING', 'FAILED') OR d.current_version_id = v.id)
                ORDER BY v.version_number DESC
                LIMIT 1
                """)
                .param("documentId", documentId).param("hash", contentHash)
                .query((rs, row) -> new ExistingVersion(rs.getObject("id", UUID.class), rs.getInt("version_number"),
                        rs.getString("index_status"))).optional();
    }

    private VersionReservation reuseOrRetry(ExistingVersion existing, ValidatedUpload upload, UUID newJobId) {
        if (STATUS_INDEXING.equals(existing.status())) {
            throw new IngestionException("An identical document version is already being indexed");
        }
        if (STATUS_FAILED.equals(existing.status())) {
            jdbc.update("UPDATE document_versions SET index_status = 'INDEXING' WHERE id = ?", existing.versionId());
            jdbc.update("INSERT INTO ingestion_jobs (id, document_id, document_version_id, status) VALUES (?, ?, ?, 'INDEXING')",
                    newJobId, upload.documentId(), existing.versionId());
            return new VersionReservation(existing.versionId(), existing.version(), newJobId, false, false, metadata(upload));
        }
        boolean changed = applyMetadataAndPermissions(upload);
        return new VersionReservation(existing.versionId(), existing.version(), null, true, changed, metadata(upload));
    }

    private boolean applyMetadataAndPermissions(ValidatedUpload upload) {
        DocumentMetadata previous = client.sql("SELECT title, department, classification FROM documents WHERE id = :id")
                .param("id", upload.documentId()).query((rs, row) -> new DocumentMetadata(
                        rs.getString("title"), rs.getString("department"), rs.getString("classification"))).single();
        Set<String> previousRoles = Set.copyOf(client.sql("SELECT allowed_role FROM document_permissions WHERE document_id = :id")
                .param("id", upload.documentId()).query(String.class).list());
        DocumentMetadata next = metadata(upload);
        boolean changed = !previous.equals(next) || !previousRoles.equals(upload.roles());
        jdbc.update("""
                UPDATE documents SET title = ?, department = ?, classification = ?, status = 'ACTIVE', updated_at = now()
                WHERE id = ?
                """, upload.title(), upload.department(), upload.classification(), upload.documentId());
        replacePermissions(upload.documentId(), upload.roles());
        return changed;
    }

    private VersionReservation createVersion(ValidatedUpload upload, String contentHash, UUID versionId, UUID jobId) {
        int nextVersion = client.sql("SELECT COALESCE(MAX(version_number), 0) + 1 FROM document_versions WHERE document_id = :id")
                .param("id", upload.documentId()).query(Integer.class).single();
        jdbc.update("""
                INSERT INTO document_versions (id, document_id, version_number, content_hash, source_filename,
                    effective_at, index_status, embedding_model_id, embedding_dimensions)
                VALUES (?, ?, ?, ?, ?, ?, 'INDEXING', ?, ?)
                """, versionId, upload.documentId(), nextVersion, contentHash, upload.filename(),
                Timestamp.from(upload.effectiveAt()), modelId, dimensions);
        jdbc.update("INSERT INTO ingestion_jobs (id, document_id, document_version_id, status) VALUES (?, ?, ?, 'INDEXING')",
                jobId, upload.documentId(), versionId);
        return new VersionReservation(versionId, nextVersion, jobId, false, false, metadata(upload));
    }

    private static DocumentMetadata metadata(ValidatedUpload upload) {
        return new DocumentMetadata(upload.title(), upload.department(), upload.classification());
    }

    private void persistChunks(String documentId, VersionReservation reservation,
                               List<DocumentChunk> chunks, List<float[]> vectors) {
        for (int index = 0; index < chunks.size(); index++) {
            DocumentChunk chunk = chunks.get(index);
            jdbc.update("""
                    INSERT INTO document_chunks (id, document_version_id, chunk_index, chunk_text, heading_path, page_number, embedding)
                    VALUES (?, ?, ?, ?, ?, ?, ?::vector)
                    """, documentId + "-v" + reservation.version() + "-c" + chunk.index(), reservation.versionId(),
                    chunk.index(), chunk.text(), chunk.headingPath(), chunk.pageNumber(), vectorLiteral(vectors.get(index)));
        }
    }

    private void lockDocument(String documentId) {
        client.sql("SELECT id FROM documents WHERE id = :id FOR UPDATE")
                .param("id", documentId).query(String.class).single();
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder value = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                value.append(',');
            }
            value.append(Float.toString(vector[index]));
        }
        return value.append(']').toString();
    }

    private record ExistingVersion(UUID versionId, int version, String status) {}
}
