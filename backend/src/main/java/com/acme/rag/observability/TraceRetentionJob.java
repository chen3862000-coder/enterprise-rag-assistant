package com.acme.rag.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@Component
public class TraceRetentionJob {
    private final RagTraceRepository traces;
    private final int retentionDays;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Repository is a private injected collaborator used only by scheduled cleanup")
    public TraceRetentionJob(RagTraceRepository traces, @Value("${app.traces.retention-days:30}") int retentionDays) {
        this.traces = traces;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${app.traces.cleanup-cron:0 17 3 * * *}")
    public void cleanup() {
        traces.deleteOlderThan(retentionDays);
    }
}
