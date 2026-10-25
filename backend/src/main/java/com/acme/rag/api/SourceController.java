package com.acme.rag.api;

import com.acme.rag.citation.SourcePreview;
import com.acme.rag.citation.SourceRepository;
import com.acme.rag.security.CurrentIdentity;
import com.acme.rag.observability.RagMetrics;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sources")
public class SourceController {
    private final SourceRepository sources;
    private final CurrentIdentity identity;
    private final RagMetrics metrics;

    public SourceController(SourceRepository sources, CurrentIdentity identity, RagMetrics metrics) {
        this.sources = sources;
        this.identity = identity;
        this.metrics = metrics;
    }

    @GetMapping("/{chunkId}")
    public ResponseEntity<SourcePreview> source(@PathVariable String chunkId) {
        if (!chunkId.matches("[A-Za-z0-9_-]{1,160}")) {
            return ResponseEntity.notFound().build();
        }
        var result = sources.findAuthorized(chunkId, identity.allowedRoles());
        if (result.isEmpty()) {
            metrics.deniedAccess();
        }
        return result.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
