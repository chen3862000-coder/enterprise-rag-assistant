package com.acme.rag.api;

import com.acme.rag.document.ingestion.DocumentIngestionService;
import com.acme.rag.document.ingestion.IngestionJobStatus;
import com.acme.rag.security.CurrentIdentity;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/** ACL-scoped ingestion job polling endpoint. */
@RestController
@RequestMapping("/api/ingestions")
public class IngestionController {
    private final DocumentIngestionService ingestion;
    private final CurrentIdentity identity;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Injected Spring service collaborator is private and not exposed")
    public IngestionController(DocumentIngestionService ingestion, CurrentIdentity identity) {
        this.ingestion = ingestion;
        this.identity = identity;
    }

    /** Returns content-free job state only when the caller can access its associated document. */
    @GetMapping("/{jobId}")
    public IngestionJobStatus status(@PathVariable UUID jobId) {
        IngestionJobStatus result = ingestion.job(jobId, identity.allowedRoles(), identity.isDocumentAdmin());
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingestion job was not found");
        }
        return result;
    }
}
