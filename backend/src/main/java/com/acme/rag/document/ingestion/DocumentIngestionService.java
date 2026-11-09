package com.acme.rag.document.ingestion;

import com.acme.rag.document.chunking.DocumentChunk;
import com.acme.rag.document.chunking.DocumentChunker;
import com.acme.rag.document.parsing.DocumentParser;
import com.acme.rag.document.parsing.ParsedDocument;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import com.acme.rag.observability.RagMetrics;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/** Validates, parses, chunks, embeds, and activates document versions while preserving the previous active version on failure. */
@Service
public class DocumentIngestionService {
    private static final java.util.Set<String> SAFE_PDF_FAILURES = java.util.Set.of(
            "PDF contains no extractable text; scanned PDFs are unsupported",
            "Unable to parse PDF document");
    private final UploadValidator validator;
    private final DocumentVersionRepository versions;
    private final EmbeddingService embeddings;
    private final List<DocumentParser> parsers;
    private final DocumentChunker chunker;
    private final RagMetrics metrics;
    private final DurableIngestionService durableIngestion;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Injected Spring collaborators are private and are not exposed")
    @org.springframework.beans.factory.annotation.Autowired
    public DocumentIngestionService(
            UploadValidator validator,
            DocumentVersionRepository versions,
            IngestionJobRepository jobs,
            IngestionJobWorkerRepository jobWorkers,
            EmbeddingService embeddings,
            List<DocumentParser> parsers,
            DocumentChunker chunker,
            RagMetrics metrics,
            DurableSourceStorage sourceStorage,
            @org.springframework.beans.factory.annotation.Value("${app.ingestion.batch-size:32}") int batchSize,
            @org.springframework.beans.factory.annotation.Value("${app.ingestion.max-attempts:8}") int maximumAttempts,
            @org.springframework.beans.factory.annotation.Value("${app.ingestion.retry-delay-ms:2000}") long retryDelayMillis,
            @org.springframework.beans.factory.annotation.Value("${app.ingestion.worker-enabled:true}") boolean workerEnabled,
            @org.springframework.beans.factory.annotation.Value("${app.ingestion.worker-concurrency:2}") int workerConcurrency,
            @org.springframework.beans.factory.annotation.Qualifier("ingestionWorkerExecutor")
            org.springframework.core.task.TaskExecutor workerExecutor) {
        this.validator = validator;
        this.versions = versions;
        this.embeddings = embeddings;
        this.parsers = List.copyOf(parsers);
        this.chunker = chunker;
        this.metrics = metrics;
        this.durableIngestion = new DurableIngestionService(validator, versions, jobs, jobWorkers, embeddings,
                parsers, chunker, metrics, sourceStorage, batchSize, maximumAttempts, workerEnabled,
                workerConcurrency, workerExecutor);
    }

    DocumentIngestionService(UploadValidator validator, DocumentVersionRepository versions,
                             IngestionJobRepository jobs, IngestionJobWorkerRepository jobWorkers,
                             EmbeddingService embeddings,
                             List<DocumentParser> parsers, DocumentChunker chunker, RagMetrics metrics)
    {
        this(validator, versions, jobs, jobWorkers, embeddings, parsers, chunker, metrics,
                new DurableSourceStorage(System.getProperty("java.io.tmpdir") + "/enterprise-rag-ingestion",
                        10_485_760),
                32, 8, 2_000, false, 2, Runnable::run);
    }

    public IngestionResult enqueue(IngestionRequest metadataRequest, java.io.InputStream input) {
        return durableIngestion.enqueue(metadataRequest, input);
    }

    /** Runs one queue poll, claiming work transactionally and committing bounded chunks with heartbeats. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${app.ingestion.worker-poll-ms:2000}")
    public void processQueuedJobs() {
        durableIngestion.processQueuedJobs();
    }

    /** Exposes only status records selected under the caller's document ACL. */
    public List<IngestionJobStatus> jobs(String documentId, Set<String> roles, boolean admin) {
        return durableIngestion.jobs(documentId, roles, admin);
    }

    /** Looks up a job only when the caller can access its associated document. */
    public IngestionJobStatus job(UUID id, Set<String> roles, boolean admin) {
        return durableIngestion.job(id, roles, admin);
    }

    /** Requeues leased jobs on process startup; committed chunk checkpoints remain available. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverWorkerLeases() {
        durableIngestion.recoverWorkerLeases();
    }

    /** Refreshes worker leases during slow extraction or embedding calls. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${app.ingestion.heartbeat-ms:30000}")
    public void heartbeatWorker() {
        durableIngestion.heartbeatWorker();
    }

    /**
     * Ingests content as a new version or returns the existing version for duplicate content.
     * A version becomes active only after processing and persistence complete successfully.
     */
    public IngestionResult ingest(IngestionRequest request) {
        ValidatedUpload upload = validate(request);
        VersionReservation reservation = reserve(upload);
        return finish(upload, reservation);
    }

    private ValidatedUpload validate(IngestionRequest request) {
        try {
            return validator.validate(request);
        } catch (IngestionException exception) {
            metrics.ingestion(false);
            throw exception;
        }
    }

    @SuppressFBWarnings(value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
            justification = "Reservation failures retain their domain/runtime type after recording the failure metric")
    private VersionReservation reserve(ValidatedUpload upload) {
        try {
            return versions.reserve(upload, ContentHasher.sha256(upload.content()));
        } catch (IngestionException | IllegalArgumentException | DataAccessException exception) {
            metrics.ingestion(false);
            throw exception;
        }
    }

    private IngestionResult finish(ValidatedUpload upload, VersionReservation reservation) {
        if (reservation.duplicate()) {
            metrics.ingestion(true);
            return duplicateResult(upload.documentId(), reservation);
        }
        try {
            List<DocumentChunk> chunks = chunk(upload);
            List<float[]> vectors = embeddings.embed(chunks.stream().map(DocumentChunk::text).toList());
            versions.activate(upload.documentId(), upload.roles(), reservation, chunks, vectors);
            metrics.ingestion(true);
            return result(upload.documentId(), reservation, chunks.size());
        } catch (IngestionException exception) {
            durableIngestion.fail(upload.documentId(), reservation, exception, upload.seedManaged());
            metrics.ingestion(false);
            throw exception;
        } catch (IllegalArgumentException | DataAccessException exception) {
            durableIngestion.fail(upload.documentId(), reservation, exception, upload.seedManaged());
            metrics.ingestion(false);
            throw new IngestionException(IngestionException.Kind.UNPROCESSABLE, safeFailureMessage(exception), exception);
        }
    }

    static String safeFailureMessage(RuntimeException exception) {
        Throwable cause = exception;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof IllegalArgumentException && SAFE_PDF_FAILURES.contains(cause.getMessage())) {
            return cause.getMessage();
        }
        return "Document indexing failed";
    }

    boolean isActiveContent(String documentId, byte[] content) {
        return versions.isActiveContent(documentId, ContentHasher.sha256(content));
    }

    boolean isInactiveDocument(String documentId) {
        return versions.isInactiveDocument(documentId);
    }

    boolean isRetryableSeedDocument(String documentId) {
        return versions.isRetryableSeedDocument(documentId);
    }

    private List<DocumentChunk> chunk(ValidatedUpload upload) {
        DocumentParser parser = parsers.stream().filter(candidate -> candidate.supports(upload.filename()))
                .findFirst().orElseThrow(() -> new IngestionException("No parser supports the uploaded document"));
        ParsedDocument parsed = parser.parse(upload.content());
        List<DocumentChunk> chunks = chunker.chunk(parsed);
        if (chunks.isEmpty()) {
            throw new IngestionException("Document contains no extractable text");
        }
        return chunks;
    }

    private IngestionResult duplicateResult(String documentId, VersionReservation reservation) {
        int chunkCount = versions.countChunks(reservation.versionId());
        return new IngestionResult(documentId, reservation.versionId(), null, reservation.version(), chunkCount,
                true, reservation.metadataUpdated());
    }

    private static IngestionResult result(String documentId, VersionReservation reservation, int chunkCount) {
        return new IngestionResult(documentId, reservation.versionId(), reservation.jobId(), reservation.version(),
                chunkCount, false, false);
    }
}
