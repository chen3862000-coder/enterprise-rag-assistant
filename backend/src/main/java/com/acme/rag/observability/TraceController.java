package com.acme.rag.observability;

import com.acme.rag.security.CurrentIdentity;
import com.acme.rag.security.DemoIdentityService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/** Exposes bounded trace details only to the trace owner or a document administrator. */
@RestController
@RequestMapping("/api/traces")
public class TraceController {
    private final RagTraceRepository traces;
    private final CurrentIdentity identity;
    private final DemoIdentityService identities;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Injected service collaborators are private and not exposed by this transport controller")
    public TraceController(RagTraceRepository traces, CurrentIdentity identity, DemoIdentityService identities) {
        this.traces = traces;
        this.identity = identity;
        this.identities = identities;
    }

    /** Returns not found for traces outside the caller's owner/admin scope. */
    @GetMapping("/{traceId}")
    public ResponseEntity<RagTrace> get(@PathVariable UUID traceId) {
        String principal = identity.username();
        return traces.find(traceId, principal, identities.find(principal).orElseThrow().documentAdmin())
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
