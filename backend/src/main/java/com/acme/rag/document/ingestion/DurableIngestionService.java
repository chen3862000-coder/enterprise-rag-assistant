package com.acme.rag.document.ingestion;

import com.acme.rag.document.chunking.DocumentChunker;
import com.acme.rag.document.parsing.DocumentParser;
import com.acme.rag.observability.RagMetrics;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.core.task.TaskExecutor;

/** Coordinates durable upload storage, worker polling, status authorization, and lease recovery. */
class DurableIngestionService {
    private final UploadValidator validator;
    private final DocumentVersionRepository versions;
    private final IngestionJobRepository jobs;
    private final IngestionJobWorkerRepository jobWorkers;
    private final DurableSourceStorage sourceStorage;
    private final int maximumAttempts;
    private final boolean workerEnabled;
    private final TaskExecutor workerExecutor;
    private final String workerId = UUID.randomUUID().toString();
    private final java.util.concurrent.ConcurrentMap<UUID, IngestionJob> activeJobs = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.Semaphore workerSlots;
    private final IngestionJobProcessor jobProcessor;

    DurableIngestionService(UploadValidator validator, DocumentVersionRepository versions, IngestionJobRepository jobs,
                            IngestionJobWorkerRepository jobWorkers, EmbeddingService embeddings,
                            List<DocumentParser> parsers, DocumentChunker chunker, RagMetrics metrics,
                            DurableSourceStorage sourceStorage, int batchSize, int maximumAttempts,
                            boolean workerEnabled, int workerConcurrency, TaskExecutor workerExecutor) {
        this.validator = validator;
        this.versions = versions;
        this.jobs = jobs;
        this.jobWorkers = jobWorkers;
        this.sourceStorage = sourceStorage;
        this.maximumAttempts = maximumAttempts;
        this.workerEnabled = workerEnabled;
        this.workerSlots = new java.util.concurrent.Semaphore(workerConcurrency);
        this.workerExecutor = workerExecutor;
        this.jobProcessor = new IngestionJobProcessor(jobs, jobWorkers, embeddings, parsers, chunker, metrics,
                sourceStorage, batchSize, maximumAttempts);
    }

    /** Stores and queues an upload, returning before parsing or embedding begins. */
    IngestionResult enqueue(IngestionRequest metadataRequest, java.io.InputStream input) {
        ValidatedUpload metadata = validator.validateMetadata(metadataRequest);
        DurableSourceStorage.StoredSource source = sourceStorage.store(input);
        boolean metadataStored = false;
        boolean reservationCompleted = false;
        boolean preserveReferencedSource = false;
        try {
            validator.validateFile(metadata.filename(), source.path());
            if (jobWorkers.activeJobForDocument(metadata.documentId())) {
                throw new IngestionException(IngestionException.Kind.CONFLICT,
                        "A document version is currently being indexed; wait for it to finish before retrying");
            }
            sourceStorage.writeMetadata(source, metadata);
            metadataStored = true;
            VersionReservation reservation = versions.reserveDurable(metadata, source.sha256(), source.path(), source.size());
            if (reservation.duplicate()) {
                sourceStorage.discardUnreferenced(source.path(), jobWorkers::sourceIsReferenced);
                reservationCompleted = true;
                return duplicateResult(metadata.documentId(), reservation);
            }
            reservationCompleted = true;
            return new IngestionResult(metadata.documentId(), reservation.versionId(), reservation.jobId(),
                    reservation.version(), 0, false, false);
        } catch (DataAccessException exception) {
            preserveReferencedSource = true;
            throw exception;
        } finally {
            if (!reservationCompleted) {
                if (!metadataStored) {
                    sourceStorage.delete(source.path());
                } else if (!preserveReferencedSource) {
                    sourceStorage.discardUnreferenced(source.path(), jobWorkers::sourceIsReferenced);
                }
            }
        }
    }

    /** Claims available jobs and schedules each under a bounded in-process worker slot. */
    void processQueuedJobs() {
        if (!workerEnabled) {
            return;
        }
        jobs.reapExhaustedJobs(maximumAttempts);
        int capacity = workerSlots.availablePermits();
        if (capacity == 0) {
            return;
        }
        for (IngestionJob job : jobWorkers.claimJobs(workerId, maximumAttempts, capacity)) {
            if (!workerSlots.tryAcquire()) {
                jobWorkers.releaseJob(job);
                continue;
            }
            activeJobs.put(job.id(), job);
            workerExecutor.execute(() -> {
                try {
                    jobProcessor.process(job);
                } finally {
                    activeJobs.remove(job.id());
                    workerSlots.release();
                }
            });
        }
    }

    /** Returns status rows only after the caller passes the document ACL check. */
    List<IngestionJobStatus> jobs(String documentId, Set<String> roles, boolean admin) {
        if (!jobs.canAccessDocument(documentId, roles, admin)) {
            return List.of();
        }
        return jobs.jobsForDocument(documentId, roles, admin);
    }

    /** Looks up a job only when the SQL query admits access to its associated document. */
    IngestionJobStatus job(UUID id, Set<String> roles, boolean admin) {
        return jobs.job(id, roles, admin).orElse(null);
    }

    /** Recovers stale leases and removes durable source files not referenced by active jobs. */
    void recoverWorkerLeases() {
        if (workerEnabled) {
            jobs.recoverLeases();
            sourceStorage.discardOrphanSources(jobWorkers::sourceIsReferenced);
            sourceStorage.discardStaleSources(jobWorkers::sourceIsReferenced, java.time.Duration.ofDays(7));
        }
    }

    /** Refreshes worker and active-job heartbeats during long-running extraction or embedding calls. */
    void heartbeatWorker() {
        if (workerEnabled) {
            jobWorkers.heartbeat(workerId);
            activeJobs.values().forEach(jobWorkers::heartbeat);
        }
    }

    /** Persists a synchronous processing failure with its version and document state atomically. */
    void fail(String documentId, VersionReservation reservation, RuntimeException failure, boolean seedManaged) {
        jobs.fail(documentId, reservation, failure, seedManaged);
    }

    private IngestionResult duplicateResult(String documentId, VersionReservation reservation) {
        int chunkCount = versions.countChunks(reservation.versionId());
        return new IngestionResult(documentId, reservation.versionId(), null, reservation.version(), chunkCount,
                true, reservation.metadataUpdated());
    }
}
