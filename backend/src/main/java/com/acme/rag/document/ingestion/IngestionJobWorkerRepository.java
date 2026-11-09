package com.acme.rag.document.ingestion;

import com.acme.rag.document.chunking.DocumentChunk;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns worker claims, chunk checkpoints, and lease maintenance for durable ingestion jobs. */
@Repository
class IngestionJobWorkerRepository {
    private final JdbcTemplate jdbc;
    private final JdbcClient client;
    private final TransactionTemplate transactions;

    IngestionJobWorkerRepository(JdbcTemplate jdbc, JdbcClient client, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.client = client;
        this.transactions = transactions;
    }

    /** Claims queued durable jobs under row locks, assigning a bounded worker lease. */
    List<IngestionJob> claimJobs(String workerId, int maximumAttempts, int batchSize) {
        return transactions.execute(status -> client.sql("""
                WITH candidates AS (
                    SELECT j.id FROM ingestion_jobs j
                    JOIN document_versions v ON v.id = j.document_version_id
                    WHERE j.status = 'INDEXING' AND j.source_path IS NOT NULL AND j.worker_id IS NULL
                      AND j.next_attempt_at <= now() AND j.attempt_count < :maximumAttempts
                      AND v.index_status = 'INDEXING'
                    ORDER BY j.queued_at LIMIT :batchSize FOR UPDATE OF j SKIP LOCKED
                )
                UPDATE ingestion_jobs j SET worker_id = :worker, heartbeat_at = now(),
                    progress_chunks = (SELECT count(*) FROM document_chunks c WHERE c.document_version_id = j.document_version_id),
                    attempt_count = j.attempt_count + 1,
                    started_at = CASE WHEN j.started_at IS NULL THEN now() ELSE j.started_at END
                FROM candidates c WHERE j.id = c.id
                RETURNING j.id, j.document_id, j.document_version_id, j.source_path, j.source_filename,
                    j.progress_chunks, j.attempt_count, j.worker_id,
                    (SELECT version_number FROM document_versions WHERE id=j.document_version_id) AS version_number
                """).param("worker", workerId).param("maximumAttempts", maximumAttempts).param("batchSize", batchSize)
                .query((rs, row) -> new IngestionJob(rs.getObject("id", UUID.class), rs.getString("document_id"),
                        rs.getObject("document_version_id", UUID.class), Path.of(rs.getString("source_path")),
                        rs.getString("source_filename"), rs.getInt("progress_chunks"), rs.getInt("attempt_count"),
                        rs.getString("worker_id"), rs.getInt("version_number"))).list());
    }

    /** Persists a chunk batch only while its worker lease and active document remain valid. */
    boolean stageBatch(IngestionJob job, List<DocumentChunk> chunks, List<float[]> vectors) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            Integer valid = client.sql("""
                    SELECT count(*) FROM ingestion_jobs j JOIN document_versions v ON v.id=j.document_version_id
                    JOIN documents d ON d.id=j.document_id
                    WHERE j.id=:job AND j.status='INDEXING' AND j.worker_id=:worker
                      AND v.index_status='INDEXING' AND d.status='ACTIVE'
                    """).param("job", job.id()).param("worker", job.workerId()).query(Integer.class).single();
            if (valid == 0) {
                return false;
            }
            for (int index = 0; index < chunks.size(); index++) {
                DocumentChunk chunk = chunks.get(index);
                jdbc.update("""
                        INSERT INTO document_chunks (id, document_version_id, chunk_index, chunk_text, heading_path, page_number, embedding)
                        VALUES (?, ?, ?, ?, ?, ?, ?::vector) ON CONFLICT (document_version_id, chunk_index) DO NOTHING
                        """, job.documentId() + "-v" + job.versionNumber() + "-c" + chunk.index(), job.versionId(),
                        chunk.index(), chunk.text(), chunk.headingPath(), chunk.pageNumber(), vectorLiteral(vectors.get(index)));
            }
            jdbc.update("""
                    UPDATE ingestion_jobs SET heartbeat_at=now(),
                        progress_chunks=(SELECT count(*) FROM document_chunks WHERE document_version_id=?)
                    WHERE id=? AND worker_id=?
                    """, job.versionId(), job.id(), job.workerId());
            return true;
        }));
    }

    /** Updates the expected final chunk count for the currently leased job. */
    void setTotalChunks(IngestionJob job, int total) {
        jdbc.update("UPDATE ingestion_jobs SET progress_total_chunks=?, heartbeat_at=now() "
                + "WHERE id=? AND status='INDEXING' AND worker_id=?", total, job.id(), job.workerId());
    }

    /** Reports whether an active job still references the normalized durable source path. */
    boolean sourceIsReferenced(Path source) {
        return Boolean.TRUE.equals(client.sql("SELECT EXISTS(SELECT 1 FROM ingestion_jobs WHERE source_path=:path AND status='INDEXING')")
                .param("path", source.toAbsolutePath().normalize().toString()).query(Boolean.class).single());
    }

    /** Counts active leases owned by the named worker. */
    int claimedJobCount(String workerId) {
        return client.sql("SELECT count(*) FROM ingestion_jobs WHERE worker_id=:worker AND status='INDEXING'")
                .param("worker", workerId).query(Integer.class).single();
    }

    /** Refreshes heartbeats for all active jobs owned by a worker. */
    void heartbeat(String workerId) {
        jdbc.update("UPDATE ingestion_jobs SET heartbeat_at=now() WHERE status='INDEXING' AND worker_id=?", workerId);
    }

    /** Refreshes the heartbeat while the job remains owned by its worker. */
    void heartbeat(IngestionJob job) {
        jdbc.update("UPDATE ingestion_jobs SET heartbeat_at=now() WHERE id=? AND status='INDEXING' AND worker_id=?",
                job.id(), job.workerId());
    }

    /** Checks both the lease and active document/version state before expensive work. */
    boolean jobIsActive(IngestionJob job) {
        return Boolean.TRUE.equals(client.sql("""
                SELECT EXISTS(SELECT 1 FROM ingestion_jobs j JOIN documents d ON d.id=j.document_id
                    JOIN document_versions v ON v.id=j.document_version_id
                    WHERE j.id=:id AND j.worker_id=:worker AND j.status='INDEXING'
                      AND v.index_status='INDEXING' AND d.status='ACTIVE')
                """).param("id", job.id()).param("worker", job.workerId()).query(Boolean.class).single());
    }

    /** Checks whether a job remains in the caller's worker lease. */
    boolean jobIsOwned(IngestionJob job) {
        return Boolean.TRUE.equals(client.sql("SELECT EXISTS(SELECT 1 FROM ingestion_jobs WHERE id=:id "
                        + "AND worker_id=:worker AND status='INDEXING')")
                .param("id", job.id()).param("worker", job.workerId()).query(Boolean.class).single());
    }

    /** Releases a lease without changing the durable job's retry state. */
    void releaseJob(IngestionJob job) {
        jdbc.update("UPDATE ingestion_jobs SET worker_id=NULL,heartbeat_at=now() WHERE id=? AND worker_id=? AND status='INDEXING'",
                job.id(), job.workerId());
    }

    /** Checks whether a document already has a durable indexing job. */
    boolean activeJobForDocument(String documentId) {
        return Boolean.TRUE.equals(client.sql("SELECT EXISTS(SELECT 1 FROM ingestion_jobs "
                        + "WHERE document_id=:id AND status='INDEXING')")
                .param("id", documentId).query(Boolean.class).single());
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
}
