package com.acme.rag.document.ingestion;

import com.acme.rag.document.chunking.DocumentChunk;
import com.acme.rag.document.chunking.DocumentChunker;
import com.acme.rag.document.parsing.DocumentParser;
import com.acme.rag.document.parsing.ParsedDocument;
import com.acme.rag.observability.RagMetrics;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.springframework.dao.DataAccessException;

/** Processes one claimed durable job and commits its chunks and terminal state under its worker lease. */
class IngestionJobProcessor {
    private final IngestionJobRepository jobs;
    private final IngestionJobWorkerRepository jobWorkers;
    private final EmbeddingService embeddings;
    private final List<DocumentParser> parsers;
    private final DocumentChunker chunker;
    private final RagMetrics metrics;
    private final DurableSourceStorage sourceStorage;
    private final int batchSize;
    private final int maximumAttempts;

    IngestionJobProcessor(IngestionJobRepository jobs, IngestionJobWorkerRepository jobWorkers,
                          EmbeddingService embeddings, List<DocumentParser> parsers,
                          DocumentChunker chunker, RagMetrics metrics, DurableSourceStorage sourceStorage,
                          int batchSize, int maximumAttempts) {
        this.jobs = jobs;
        this.jobWorkers = jobWorkers;
        this.embeddings = embeddings;
        this.parsers = List.copyOf(parsers);
        this.chunker = chunker;
        this.metrics = metrics;
        this.sourceStorage = sourceStorage;
        this.batchSize = batchSize;
        this.maximumAttempts = maximumAttempts;
    }

    /** Processes the job if its claim is still valid, preserving committed chunk checkpoints on retry. */
    void process(IngestionJob job) {
        try {
            processClaimedJob(job);
        } catch (IngestionException exception) {
            handleIngestionFailure(job, exception);
        } catch (IllegalArgumentException | DataAccessException exception) {
            failOwnedJob(job, exception);
        }
    }

    private void processClaimedJob(IngestionJob job) {
        IngestionMetadata metadata = readAndValidateMetadata(job);
        List<DocumentChunk> chunks = parseChunks(job);
        int total = chunks.size();
        if (total == 0) {
            throw new IngestionException(IngestionException.Kind.UNPROCESSABLE, "Document contains no extractable text");
        }
        stageChunks(job, chunks, total);
        if (!completeJob(job, total, metadata)) {
            return;
        }
        metrics.ingestion(true);
        sourceStorage.delete(job.sourcePath());
    }

    private IngestionMetadata readAndValidateMetadata(IngestionJob job) {
        if (!sourceStorage.isStoredSource(job.sourcePath())) {
            jobs.failMissingSource(job);
            throw new IngestionException(IngestionException.Kind.UNPROCESSABLE, "Durable ingestion source is missing");
        }
        IngestionMetadata metadata = sourceStorage.readMetadata(job.sourcePath());
        if (!metadata.documentId().equals(job.documentId())) {
            throw new IngestionException(IngestionException.Kind.UNPROCESSABLE, "Durable ingestion metadata is invalid");
        }
        return metadata;
    }

    private void stageChunks(IngestionJob job, List<DocumentChunk> chunks, int total) {
        jobWorkers.setTotalChunks(job, total);
        int resume = Math.min(job.progressChunks(), total);
        for (int start = resume; start < total; start += batchSize) {
            stageBatch(job, chunks, start);
        }
    }

    private void stageBatch(IngestionJob job, List<DocumentChunk> chunks, int start) {
        if (!jobWorkers.jobIsActive(job)) {
            throw new IngestionException(IngestionException.Kind.CONFLICT, "Ingestion lease or document state changed");
        }
        List<DocumentChunk> batch = chunks.subList(start, Math.min(start + batchSize, chunks.size()));
        List<float[]> vectors = embeddings.embed(batch.stream().map(DocumentChunk::text).toList());
        if (!jobWorkers.stageBatch(job, batch, vectors)) {
            throw new IngestionException(IngestionException.Kind.CONFLICT, "Ingestion worker lease has changed");
        }
    }

    private boolean completeJob(IngestionJob job, int total, IngestionMetadata metadata) {
        try {
            jobs.completeJob(job, total, metadata);
            return true;
        } catch (DataAccessException exception) {
            if (jobWorkers.jobIsOwned(job)) {
                jobWorkers.releaseJob(job);
            }
            return false;
        }
    }

    private void handleIngestionFailure(IngestionJob job, IngestionException exception) {
        if (!jobWorkers.jobIsOwned(job)) {
            return;
        }
        if (exception.kind() == IngestionException.Kind.CONFLICT) {
            jobWorkers.releaseJob(job);
        } else if (exception.kind() == IngestionException.Kind.PROVIDER_UNAVAILABLE) {
            retryOrDiscard(job);
        } else {
            failOwnedJob(job, exception);
        }
    }

    private void retryOrDiscard(IngestionJob job) {
        boolean deferred = jobs.retryJob(job, "Embedding provider temporarily unavailable", maximumAttempts);
        if (!deferred) {
            sourceStorage.discardUnreferenced(job.sourcePath(), jobWorkers::sourceIsReferenced);
        }
    }

    private void failOwnedJob(IngestionJob job, RuntimeException exception) {
        if (!jobWorkers.jobIsOwned(job)) {
            return;
        }
        jobs.failJob(job, DocumentIngestionService.safeFailureMessage(exception));
        sourceStorage.discardUnreferenced(job.sourcePath(), jobWorkers::sourceIsReferenced);
        metrics.ingestion(false);
    }

    private List<DocumentChunk> parseChunks(IngestionJob job) {
        try {
            if (!sourceStorage.isStoredSource(job.sourcePath())) {
                throw new IngestionException(IngestionException.Kind.UNPROCESSABLE, "Durable ingestion source is missing");
            }
            byte[] content = Files.readAllBytes(job.sourcePath());
            DocumentParser parser = parsers.stream().filter(candidate -> candidate.supports(job.filename()))
                    .findFirst().orElseThrow(() -> new IngestionException("No parser supports the uploaded document"));
            ParsedDocument parsed = parser.parse(content);
            return chunker.chunk(parsed);
        } catch (IOException exception) {
            throw new IngestionException(IngestionException.Kind.UNPROCESSABLE,
                    "Unable to read durable ingestion source", exception);
        }
    }
}
