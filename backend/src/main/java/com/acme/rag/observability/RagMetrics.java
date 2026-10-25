package com.acme.rag.observability;

import com.acme.rag.AccessDeniedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@Component
public final class RagMetrics {
    private final MeterRegistry registry;
    private final Timer retrievalLatency;
    private final Timer generationLatency;
    private final Counter requests;
    private final Counter emptyRetrievals;
    private final Counter invalidCitations;
    private final Counter evaluationRuns;
    private final Counter ingestionSuccess;
    private final Counter ingestionFailure;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "MeterRegistry is an injected service collaborator retained only to register bounded metrics")
    public RagMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.retrievalLatency = registry.timer("rag.retrieval.duration");
        this.generationLatency = registry.timer("rag.generation.duration");
        this.requests = registry.counter("rag.requests");
        this.emptyRetrievals = registry.counter("rag.retrieval.empty");
        this.invalidCitations = registry.counter("rag.citations.invalid");
        this.evaluationRuns = registry.counter("rag.evaluations.runs", "method", "OFFLINE_RETRIEVAL");
        this.ingestionSuccess = registry.counter("rag.ingestion", "result", "success");
        this.ingestionFailure = registry.counter("rag.ingestion", "result", "failure");
    }

    public void retrieval(long nanos) {
        retrievalLatency.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void generation(long nanos) {
        generationLatency.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void request() {
        requests.increment();
    }

    public void emptyRetrieval() {
        emptyRetrievals.increment();
    }

    public void invalidCitation() {
        invalidCitations.increment();
    }

    public void evaluationRun() {
        evaluationRuns.increment();
    }

    public void deniedAccess() {
        registry.counter("rag.acl.denied").increment();
    }

    @EventListener
    public void deniedAccess(AccessDeniedEvent event) {
        deniedAccess();
    }

    public void ingestion(boolean succeeded) {
        if (succeeded) {
            ingestionSuccess.increment();
        } else {
            ingestionFailure.increment();
        }
    }
}
