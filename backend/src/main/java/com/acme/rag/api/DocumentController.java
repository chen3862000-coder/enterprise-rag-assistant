package com.acme.rag.api;

import com.acme.rag.document.ingestion.DocumentIngestionService;
import com.acme.rag.document.ingestion.DocumentManagementRepository;
import com.acme.rag.document.ingestion.DocumentSummary;
import com.acme.rag.document.ingestion.DocumentVersionSummary;
import com.acme.rag.document.ingestion.IngestionRequest;
import com.acme.rag.document.ingestion.IngestionResult;
import com.acme.rag.security.CurrentIdentity;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/** Document listing and administration endpoints; mutations require explicit document-admin capability. */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentIngestionService ingestion;
    private final DocumentManagementRepository documents;
    private final CurrentIdentity identity;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Controller stores private Spring service collaborators and does not expose them")
    public DocumentController(DocumentIngestionService ingestion, DocumentManagementRepository documents, CurrentIdentity identity) {
        this.ingestion = ingestion;
        this.documents = documents;
        this.identity = identity;
    }

    /** Lists documents visible to the caller's roles, or all documents for a document administrator. */
    @GetMapping
    public List<DocumentSummary> list() {
        if (identity.isDocumentAdmin()) {
            return documents.listAllForAdmin();
        }
        return documents.list(identity.allowedRoles());
    }

    /**
     * Uploads content as a new logical document or a new version when the ID already exists.
     * Multipart fields are {@code file}, {@code documentId}, {@code title}, {@code department},
     * {@code classification}, and comma-separated {@code allowedRoles}.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestionResult upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam("documentId") String documentId,
            @RequestParam("title") String title,
            @RequestParam("department") String department,
            @RequestParam("classification") String classification,
            @RequestParam("allowedRoles") String roleCsv) {
        identity.requireDocumentAdmin();
        try {
            return ingestion.ingest(new IngestionRequest(documentId, title, department, classification,
                    roles(roleCsv), file.getOriginalFilename(), file.getBytes(), Instant.now()));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read uploaded file", exception);
        }
    }

    /**
     * Creates a new version for the specified logical document. Multipart fields are {@code file},
     * {@code title}, {@code department}, {@code classification}, and comma-separated {@code allowedRoles}.
     */
    @PostMapping(value = "/{documentId}/reindex", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestionResult reindex(@PathVariable String documentId, @RequestPart("file") MultipartFile file,
                                   @RequestParam("title") String title, @RequestParam("department") String department,
                                   @RequestParam("classification") String classification,
                                   @RequestParam("allowedRoles") String roleCsv) {
        identity.requireDocumentAdmin();
        try {
            return ingestion.ingest(new IngestionRequest(documentId, title, department, classification,
                    roles(roleCsv), file.getOriginalFilename(), file.getBytes(), Instant.now()));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read uploaded file", exception);
        }
    }

    /** Lists version metadata only when the caller can access the document, unless they are an administrator. */
    @GetMapping("/{documentId}/versions")
    public List<DocumentVersionSummary> versions(@PathVariable String documentId) {
        if (identity.isDocumentAdmin()) {
            return documents.versionsAllForAdmin(documentId);
        }
        return documents.versions(documentId, identity.allowedRoles());
    }

    /** Disables retrieval for a document while retaining its record and version data. */
    @PostMapping("/{documentId}/disable")
    public ResponseEntity<Void> disable(@PathVariable String documentId) {
        identity.requireDocumentAdmin();
        if (!documents.disable(documentId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    /** Soft-deletes the document; retained chunks are excluded from retrieval rather than physically purged. */
    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable String documentId) {
        identity.requireDocumentAdmin();
        if (!documents.delete(documentId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    /** Restores a disabled or soft-deleted logical document; this does not reprocess its content. */
    @PostMapping("/{documentId}/restore")
    public ResponseEntity<Void> restore(@PathVariable String documentId) {
        identity.requireDocumentAdmin();
        if (!documents.restore(documentId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    private static Set<String> roles(String roleCsv) {
        if (roleCsv == null || roleCsv.isBlank()) {
            throw new com.acme.rag.document.ingestion.IngestionException(
                    com.acme.rag.document.ingestion.IngestionException.Kind.INVALID_INPUT,
                    "At least one allowed role is required");
        }
        return java.util.Arrays.stream(roleCsv.split(","))
                .map(String::strip).filter(role -> !role.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
