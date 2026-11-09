package com.acme.rag.document.ingestion;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists durable worker leases, job lifecycle transitions, and ACL-scoped status. */
@Repository
class IngestionJobRepository {
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final JdbcClient client;
    private final TransactionTemplate transactions;

    IngestionJobRepository(org.springframework.jdbc.core.JdbcTemplate jdbc, JdbcClient client, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.client = client;
        this.transactions = transactions;
    }

    /** Creates the durable job in the caller's reservation transaction. */
    void createReservedJob(UUID jobId, String documentId, UUID versionId, String sourcePath,
                           String filename, Long sourceSize) {
        jdbc.update("""
                INSERT INTO ingestion_jobs (id, document_id, document_version_id, status, source_path,
                    source_filename, source_size_bytes, progress_chunks, progress_total_chunks, attempt_count,
                    next_attempt_at)
                VALUES (?, ?, ?, 'INDEXING', ?, ?, ?, 0, NULL, 0, now())
                """, jobId, documentId, versionId, sourcePath, filename, sourceSize);
        jdbc.update("UPDATE ingestion_jobs SET heartbeat_at=now() WHERE id=?", jobId);
    }

    /** Restarts a failed version's durable job in the caller's reservation transaction. */
    void restartFailedVersion(UUID oldVersionId, UUID newJobId, String documentId, String sourcePath,
                              String filename, Long sourceSize) {
        jdbc.update("UPDATE ingestion_jobs SET status = 'FAILED', completed_at = now(), worker_id = NULL "
                + "WHERE document_version_id = ? AND status = 'INDEXING'", oldVersionId);
        createReservedJob(newJobId, documentId, oldVersionId, sourcePath, filename, sourceSize);
    }

    /** Checks whether a durable indexing job exists for the document. */
    boolean hasActiveDurableJob(String documentId) {
        return Boolean.TRUE.equals(client.sql("SELECT EXISTS(SELECT 1 FROM ingestion_jobs "
                        + "WHERE document_id=:id AND status='INDEXING' AND source_path IS NOT NULL)")
                .param("id", documentId).query(Boolean.class).single());
    }

    /** Marks a job successful as part of the caller's version activation transaction. */
    void markSucceeded(UUID jobId) {
        jdbc.update("UPDATE ingestion_jobs SET status = 'SUCCEEDED', completed_at = now() WHERE id = ?", jobId);
    }

    /** Atomically activates the staged version, applies supplied metadata/ACL, and completes its job. */
    void completeJob(IngestionJob job, int expectedChunks, IngestionMetadata metadata) {
        transactions.executeWithoutResult(status -> {
            String documentStatus = client.sql("SELECT status FROM documents WHERE id = :id FOR UPDATE")
                    .param("id", job.documentId()).query(String.class).single();
            UUID owned = client.sql("SELECT id FROM ingestion_jobs WHERE id=:id AND status='INDEXING' "
                            + "AND worker_id=:worker FOR UPDATE")
                    .param("id", job.id()).param("worker", job.workerId()).query(UUID.class).optional().orElse(null);
            String versionStatus = client.sql("SELECT index_status FROM document_versions WHERE id=:id FOR UPDATE")
                    .param("id", job.versionId()).query(String.class).optional().orElse(null);
            Integer actual = client.sql("SELECT count(*) FROM document_chunks WHERE document_version_id=:id")
                    .param("id", job.versionId()).query(Integer.class).single();
            if (!"ACTIVE".equals(documentStatus) || !job.id().equals(owned) || !"INDEXING".equals(versionStatus)
                    || actual == 0 || actual != expectedChunks) {
                throw new IngestionException(IngestionException.Kind.CONFLICT,
                        "Ingestion lease or staged version is no longer active");
            }
            jdbc.update("UPDATE document_versions SET index_status='SUPERSEDED' WHERE document_id=? AND index_status='ACTIVE'",
                    job.documentId());
            jdbc.update("UPDATE document_versions SET index_status='ACTIVE' WHERE id=? AND index_status='INDEXING'",
                    job.versionId());
            jdbc.update("UPDATE documents SET current_version_id=?,updated_at=now() WHERE id=? AND status='ACTIVE'",
                    job.versionId(), job.documentId());
            if (metadata != null) {
                updateMetadata(job.documentId(), new DocumentMetadata(metadata.title(), metadata.department(), metadata.classification()));
                replacePermissions(job.documentId(), metadata.roles());
            }
            jdbc.update("UPDATE ingestion_jobs SET status='SUCCEEDED',completed_at=now(),worker_id=NULL WHERE id=?", job.id());
        });
    }

    /** Fails a worker-owned job and makes an unversioned document unavailable. */
    void failJob(IngestionJob job, String message) {
        transactions.executeWithoutResult(status -> {
            String safe = message == null ? "Document indexing failed" : message.substring(0, Math.min(message.length(), 500));
            int updated = jdbc.update("UPDATE ingestion_jobs SET status='FAILED',error_message=?,completed_at=now(),worker_id=NULL "
                    + "WHERE id=? AND worker_id=? AND status='INDEXING'", safe, job.id(), job.workerId());
            if (updated == 1) {
                markVersionFailed(job.versionId());
                disableUnversionedDocument(job.documentId());
            }
        });
    }

    /** Fails a leased job whose durable source file has disappeared. */
    void failMissingSource(IngestionJob job) {
        transactions.executeWithoutResult(status -> {
            int updated = jdbc.update("UPDATE ingestion_jobs SET status='FAILED',error_message='Durable ingestion source is missing',"
                    + "completed_at=now(),worker_id=NULL WHERE id=? AND worker_id=? AND status='INDEXING'", job.id(), job.workerId());
            if (updated == 1) {
                markVersionFailed(job.versionId());
                disableUnversionedDocument(job.documentId());
            }
        });
    }

    /** Releases a lease for bounded retry, failing it when its attempt budget is exhausted. */
    boolean retryJob(IngestionJob job, String safeMessage, int maximumAttempts) {
        int updated = jdbc.update("""
                UPDATE ingestion_jobs SET worker_id=NULL,
                    error_message=CASE WHEN attempt_count >= ? THEN 'Embedding provider retry limit exceeded' ELSE ? END,
                    heartbeat_at=now(),
                    next_attempt_at=now()+make_interval(secs=>LEAST(300,power(2,LEAST(attempt_count,8))::integer)),
                    status=CASE WHEN attempt_count >= ? THEN 'FAILED' ELSE status END,
                    completed_at=CASE WHEN attempt_count >= ? THEN now() ELSE completed_at END
                WHERE id=? AND worker_id=? AND status='INDEXING'
                """, maximumAttempts, safeMessage, maximumAttempts, maximumAttempts, job.id(), job.workerId());
        if (updated == 1 && job.attemptCount() >= maximumAttempts) {
            markVersionFailed(job.versionId());
            disableUnversionedDocument(job.documentId());
            return false;
        }
        return updated == 1;
    }

    /** Returns recent jobs only when the SQL permission predicate admits the caller. */
    List<IngestionJobStatus> jobsForDocument(String documentId, Set<String> roles, boolean admin) {
        return client.sql(statusQuery() + " WHERE d.id=:document AND " + authorizedPredicate()
                + " ORDER BY j.queued_at DESC LIMIT 10")
                .param("document", documentId).param("admin", admin).param("roles", roleParameters(roles))
                .query((rs, row) -> status(rs)).list();
    }

    /** Resolves a job only when the SQL permission predicate admits its document. */
    Optional<IngestionJobStatus> job(UUID id, Set<String> roles, boolean admin) {
        return client.sql(statusQuery() + " WHERE j.id=:id AND " + authorizedPredicate())
                .param("id", id).param("admin", admin).param("roles", roleParameters(roles))
                .query((rs, row) -> status(rs)).optional();
    }

    /** Checks access before returning document-level status to the service. */
    boolean canAccessDocument(String documentId, Set<String> roles, boolean admin) {
        return Boolean.TRUE.equals(client.sql("""
                SELECT EXISTS(SELECT 1 FROM documents d WHERE d.id=:id AND
                    (:admin OR EXISTS(SELECT 1 FROM document_permissions p WHERE p.document_id=d.id
                        AND p.allowed_role IN (:roles))))
                """).param("id", documentId).param("admin", admin).param("roles", roleParameters(roles))
                .query(Boolean.class).single());
    }

    /** Recovers stale leases and fails non-durable jobs that cannot resume. */
    int recoverLeases() {
        return transactions.execute(status -> {
            jdbc.update("UPDATE ingestion_jobs SET worker_id=NULL,heartbeat_at=now(),next_attempt_at=now() "
                    + "WHERE status='INDEXING' AND source_path IS NOT NULL AND heartbeat_at < now() - interval '1 hour'");
            int failed = jdbc.update("""
                    UPDATE ingestion_jobs SET status='FAILED',error_message='Indexing lease expired',completed_at=now()
                    WHERE status='INDEXING' AND source_path IS NULL AND heartbeat_at < now() - interval '1 hour'
                    """);
            jdbc.update("UPDATE document_versions v SET index_status='FAILED' WHERE v.index_status='INDEXING' "
                    + "AND EXISTS(SELECT 1 FROM ingestion_jobs j WHERE j.document_version_id=v.id AND j.status='FAILED' "
                    + "AND j.error_message='Indexing lease expired')");
            return failed;
        });
    }

    /** Fails queued jobs whose retry budget has elapsed and updates associated versions atomically. */
    int reapExhaustedJobs(int maximumAttempts) {
        return transactions.execute(status -> {
            List<IngestionJob> exhausted = client.sql("""
                    SELECT id,document_id,document_version_id,source_path,source_filename,progress_chunks,attempt_count,
                        worker_id,(SELECT version_number FROM document_versions WHERE id=document_version_id) AS version_number
                    FROM ingestion_jobs WHERE status='INDEXING' AND source_path IS NOT NULL AND attempt_count >= ?
                      AND next_attempt_at <= now() AND worker_id IS NULL
                    FOR UPDATE SKIP LOCKED
                    """).param(1, maximumAttempts)
                    .query((rs, row) -> new IngestionJob(rs.getObject("id", UUID.class), rs.getString("document_id"),
                            rs.getObject("document_version_id", UUID.class), Path.of(rs.getString("source_path")),
                            rs.getString("source_filename"), rs.getInt("progress_chunks"), rs.getInt("attempt_count"),
                            rs.getString("worker_id"), rs.getInt("version_number"))).list();
            for (IngestionJob job : exhausted) {
                failJob(job, "Embedding provider retry limit exceeded");
            }
            int failed = jdbc.update("""
                    UPDATE ingestion_jobs SET status='FAILED',error_message='Embedding provider retry limit exceeded',
                        completed_at=now(),worker_id=NULL
                    WHERE status='INDEXING' AND source_path IS NOT NULL AND attempt_count >= ?
                      AND next_attempt_at <= now() AND worker_id IS NULL
                    """, maximumAttempts);
            jdbc.update("""
                    UPDATE document_versions v SET index_status='FAILED'
                    WHERE v.index_status='INDEXING' AND EXISTS(SELECT 1 FROM ingestion_jobs j
                        WHERE j.document_version_id=v.id AND j.status='FAILED'
                          AND j.error_message='Embedding provider retry limit exceeded')
                    """);
            jdbc.update("""
                    UPDATE documents d SET status='DISABLED',seed_repairable=false
                    WHERE d.current_version_id IS NULL AND d.status='ACTIVE' AND EXISTS(
                        SELECT 1 FROM ingestion_jobs j WHERE j.document_id=d.id AND j.status='FAILED'
                          AND j.error_message='Embedding provider retry limit exceeded')
                    """);
            return failed;
        });
    }

    /** Converts a failed synchronous reservation to a terminal job state. */
    void fail(String documentId, VersionReservation reservation, RuntimeException failure, boolean seedManaged) {
        transactions.executeWithoutResult(status -> {
            String message = failure.getMessage() == null ? "Indexing failed" : failure.getMessage();
            String safeMessage = message.substring(0, Math.min(message.length(), 500));
            markVersionFailed(reservation.versionId());
            jdbc.update("UPDATE ingestion_jobs SET status = 'FAILED', error_message = ?, completed_at = now() WHERE id = ?",
                    safeMessage, reservation.jobId());
            boolean retryableProviderFailure = seedManaged && hasTransientProviderCause(failure);
            if (retryableProviderFailure) {
                jdbc.update("UPDATE documents SET status = 'DISABLED', seed_repairable = true "
                        + "WHERE id = ? AND status = 'ACTIVE' AND current_version_id IS NULL "
                        + "AND seed_managed = true", documentId);
            } else {
                disableUnversionedDocument(documentId);
            }
        });
    }

    private void updateMetadata(String documentId, DocumentMetadata metadata) {
        jdbc.update("UPDATE documents SET title = ?, department = ?, classification = ?, updated_at = now() WHERE id = ?",
                metadata.title(), metadata.department(), metadata.classification(), documentId);
    }

    private void replacePermissions(String documentId, Set<String> roles) {
        jdbc.update("DELETE FROM document_permissions WHERE document_id = ?", documentId);
        roles.forEach(role -> jdbc.update(
                "INSERT INTO document_permissions (document_id, allowed_role) VALUES (?, ?)", documentId, role));
    }

    private void markVersionFailed(UUID versionId) {
        jdbc.update("UPDATE document_versions SET index_status='FAILED' WHERE id=? AND index_status='INDEXING'", versionId);
    }

    private void disableUnversionedDocument(String documentId) {
        jdbc.update("UPDATE documents SET status='DISABLED',seed_repairable=false WHERE id=? "
                + "AND current_version_id IS NULL AND status='ACTIVE'", documentId);
    }

    private static String statusQuery() {
        return """
                SELECT j.id,j.document_id,j.document_version_id,j.status,j.error_message,j.started_at,j.completed_at,
                    j.progress_total_chunks,v.index_status,d.current_version_id,d.title,
                    (SELECT count(*) FROM document_chunks c WHERE c.document_version_id=v.id) AS progress_chunks
                FROM ingestion_jobs j JOIN document_versions v ON v.id=j.document_version_id
                JOIN documents d ON d.id=j.document_id
                """;
    }

    private static String authorizedPredicate() {
        return "(:admin OR EXISTS(SELECT 1 FROM document_permissions p "
                + "WHERE p.document_id=d.id AND p.allowed_role IN (:roles)))";
    }

    private static List<String> roleParameters(Set<String> roles) {
        return roles.isEmpty() ? List.of("__none__") : List.copyOf(roles);
    }

    private static IngestionJobStatus status(java.sql.ResultSet rs) throws SQLException {
        UUID versionId = rs.getObject("document_version_id", UUID.class);
        UUID currentVersion = rs.getObject("current_version_id", UUID.class);
        return new IngestionJobStatus(rs.getObject("id", UUID.class), rs.getString("document_id"),
                rs.getString("title"), rs.getString("status"), rs.getString("error_message"),
                rs.getInt("progress_chunks"), (Integer) rs.getObject("progress_total_chunks"),
                rs.getString("index_status"), versionId.equals(currentVersion),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());
    }

    private static boolean hasTransientProviderCause(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof org.springframework.ai.retry.TransientAiException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
