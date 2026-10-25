package com.acme.rag.document.ingestion;

import com.acme.rag.document.chunking.DocumentChunk;
import com.acme.rag.document.chunking.DocumentChunker;
import com.acme.rag.document.parsing.DocumentParser;
import com.acme.rag.document.parsing.ParsedDocument;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import com.acme.rag.observability.RagMetrics;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@Service
public class DocumentIngestionService {
    private final UploadValidator validator;
    private final DocumentVersionRepository versions;
    private final EmbeddingService embeddings;
    private final List<DocumentParser> parsers;
    private final DocumentChunker chunker;
    private final RagMetrics metrics;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "DocumentChunker is final and holds only validated primitive configuration")
    public DocumentIngestionService(
            UploadValidator validator,
            DocumentVersionRepository versions,
            EmbeddingService embeddings,
            List<DocumentParser> parsers,
            DocumentChunker chunker,
            RagMetrics metrics) {
        this.validator = validator;
        this.versions = versions;
        this.embeddings = embeddings;
        this.parsers = List.copyOf(parsers);
        this.chunker = chunker;
        this.metrics = metrics;
    }

    @SuppressFBWarnings(value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION", justification = "Reservation failures propagate their domain exception after recording an ingestion failure metric")
    public IngestionResult ingest(IngestionRequest request) {
        ValidatedUpload upload = validator.validate(request);
        VersionReservation reservation;
        try {
            reservation = versions.reserve(upload, ContentHasher.sha256(upload.content()));
        } catch (IngestionException | IllegalArgumentException | DataAccessException exception) {
            metrics.ingestion(false);
            throw exception;
        }
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
            versions.fail(upload.documentId(), reservation, exception);
            metrics.ingestion(false);
            throw exception;
        } catch (IllegalArgumentException | DataAccessException exception) {
            versions.fail(upload.documentId(), reservation, exception);
            metrics.ingestion(false);
            throw new IngestionException("Document indexing failed", exception);
        }
    }

    boolean isActiveContent(String documentId, byte[] content) {
        return versions.isActiveContent(documentId, ContentHasher.sha256(content));
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
