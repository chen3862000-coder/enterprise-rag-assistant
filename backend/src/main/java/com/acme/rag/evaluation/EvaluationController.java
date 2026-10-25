package com.acme.rag.evaluation;

import com.acme.rag.security.CurrentIdentity;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.acme.rag.observability.RagMetrics;

@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {
    private final EvaluationService evaluations;
    private final CurrentIdentity identity;
    private final RagMetrics metrics;

    public EvaluationController(EvaluationService evaluations, CurrentIdentity identity, RagMetrics metrics) {
        this.evaluations = evaluations;
        this.identity = identity;
        this.metrics = metrics;
    }

    @PostMapping("/run")
    public EvaluationRunResult run() {
        identity.requireDocumentAdmin();
        try {
            return evaluations.run(identity.require().username());
        } catch (org.springframework.security.access.AccessDeniedException exception) {
            metrics.deniedAccess();
            throw exception;
        }
    }

    @GetMapping
    public List<EvaluationRunSummary> recent(@RequestParam(defaultValue = "10") int limit) {
        identity.requireDocumentAdmin();
        return evaluations.recent(limit);
    }

    @GetMapping("/{runId}")
    public ResponseEntity<EvaluationRunResult> get(@PathVariable UUID runId) {
        identity.requireDocumentAdmin();
        return ResponseEntity.ok(evaluations.get(runId, identity.require().username()));
    }
}
