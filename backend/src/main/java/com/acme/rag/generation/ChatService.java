package com.acme.rag.generation;

import com.acme.rag.citation.CitationResponseBuilder;
import com.acme.rag.citation.CitationAccessValidator;
import com.acme.rag.citation.ContextAssembler;
import com.acme.rag.retrieval.HybridRetrievalService;
import com.acme.rag.observability.RagMetrics;
import com.acme.rag.observability.RagTrace;
import com.acme.rag.observability.RagTraceRepository;
import com.acme.rag.security.CurrentIdentity;
import com.acme.rag.document.ingestion.ContentHasher;
import java.util.UUID;
import java.time.Instant;
import org.springframework.stereotype.Service;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@Service
public class ChatService {
    private final HybridRetrievalService retrieval;
    private final ContextAssembler contextAssembler;
    private final AnswerGenerator generator;
    private final CitationResponseBuilder citations;
    private final CitationAccessValidator citationAccess;
    private final RagTraceRepository traces;
    private final RagMetrics metrics;
    private final CurrentIdentity identity;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Injected Spring service collaborators are private and not exposed")
    public ChatService(
            HybridRetrievalService retrieval,
            ContextAssembler contextAssembler,
            AnswerGenerator generator,
            CitationResponseBuilder citations,
            CitationAccessValidator citationAccess,
            RagTraceRepository traces,
            RagMetrics metrics,
            CurrentIdentity identity) {
        this.retrieval = retrieval;
        this.contextAssembler = contextAssembler;
        this.generator = generator;
        this.citations = citations;
        this.citationAccess = citationAccess;
        this.traces = traces;
        this.metrics = metrics;
        this.identity = identity;
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public ChatResponse chat(String message) {
        long startedNanos = System.nanoTime();
        Instant createdAt = Instant.now();
        metrics.request();
        var principal = identity.require();
        String traceId = UUID.randomUUID().toString();
        RequestProgress progress = new RequestProgress();
        try {
            return executeChat(message, traceId, principal.username(), createdAt, startedNanos, progress);
        } catch (RuntimeException exception) {
            return failedChat(message, traceId, principal.username(), createdAt, startedNanos, exception, progress);
        }
    }

    private ChatResponse executeChat(String message, String traceId, String username, Instant createdAt,
                                     long startedNanos, RequestProgress progress) {
        long retrievalStarted = System.nanoTime();
        com.acme.rag.retrieval.RetrievalResult result;
        try {
            result = retrieval.retrieve(message);
            progress.retrievalResult = result;
        } finally {
            progress.retrievalNanos = System.nanoTime() - retrievalStarted;
        }
        metrics.retrieval(progress.retrievalNanos);
        if (result.chunks().isEmpty()) {
            metrics.emptyRetrieval();
        }
        var context = contextAssembler.assemble(result.chunks());
        progress.contextCount = context.evidence().size();
        long generationStarted = System.nanoTime();
        String answer;
        try {
            answer = generator.generate(message, context);
        } finally {
            progress.generationNanos = System.nanoTime() - generationStarted;
        }
        if (citations.hasInvalidCitations(answer, context.evidence())) {
            metrics.invalidCitation();
        }
        String sanitizedAnswer = citations.stripUnsupportedCitationClaims(answer, context.evidence());
        var validated = citationAccess.reauthorize(citations.validate(sanitizedAnswer, context.evidence()));
        metrics.generation(progress.generationNanos);
        String grounding = result.evidenceSufficient() && !validated.citations().isEmpty()
                ? "CITATIONS_PRESENT" : "INSUFFICIENT_EVIDENCE";
        if (!generator.enabled()) {
            validated = insufficient(result.evidenceSufficient()
                    ? "Answer generation is disabled. Review the authorized source references above."
                    : "Answer generation is disabled and no sufficiently relevant authorized evidence was found.");
        } else if (!result.evidenceSufficient()) {
            validated = insufficient("I could not find sufficiently relevant authorized evidence to answer that question.");
        } else if (validated.citations().isEmpty()) {
            validated = insufficient("Citations identify supplied sources only; no answer claim was validated.");
        }
        var response = new ChatResponse(validated.answer(), validated.citations(), traceId, validated.groundingStatus());
        persistTrace(traceId, username, message, createdAt, progress.retrievalNanos, progress.generationNanos,
                System.nanoTime() - startedNanos, result, context.evidence().size(), validated.citations().size(),
                grounding, "SUCCEEDED", null);
        return response;
    }

    private ChatResponse failedChat(String message, String traceId, String username, Instant createdAt,
                                    long startedNanos, RuntimeException exception, RequestProgress progress) {
        var failedRetrieval = progress.retrievalResult == null
                ? new com.acme.rag.retrieval.RetrievalResult(java.util.List.of(), 0, 0, 0,
                        false, "REQUEST_FAILED", 0, java.util.List.of(),
                        com.acme.rag.retrieval.EvidenceSufficiencyGate.Diagnostic.empty(0))
                : progress.retrievalResult;
        String grounding = "INSUFFICIENT_EVIDENCE";
        var response = new ChatResponse("The request could not be completed safely. Please retry.",
                java.util.List.of(), traceId, grounding);
        persistTrace(traceId, username, message, createdAt, progress.retrievalNanos, progress.generationNanos,
                System.nanoTime() - startedNanos, failedRetrieval, progress.contextCount, 0,
                grounding, "FAILED", errorCategory(exception));
        return response;
    }

    private static CitationResponseBuilder.ValidatedAnswer insufficient(String answer) {
        return new CitationResponseBuilder.ValidatedAnswer(answer, java.util.List.of(), "INSUFFICIENT_EVIDENCE");
    }

    private void persistTrace(String traceId, String username, String message, Instant createdAt,
                              long retrievalNanos, long generationNanos, long totalNanos,
                              com.acme.rag.retrieval.RetrievalResult retrievalResult,
                              int contextCount, int citationCount, String groundingStatus,
                              String status, String errorCategory) {
        try {
            traces.save(new RagTrace(UUID.fromString(traceId), username, toMillis(retrievalNanos),
                    toMillis(generationNanos), toMillis(totalNanos), retrievalResult.vectorCandidates(),
                    retrievalResult.keywordCandidates(), retrievalResult.exactCandidates(), contextCount,
                    citationCount, retrievalResult.evidenceSufficient(), retrievalResult.gateReason(),
                    providerName(), activeModelId(), groundingStatus, status, errorCategory, createdAt),
                    ContentHasher.sha256(message.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (org.springframework.dao.DataAccessException exception) {
            org.slf4j.LoggerFactory.getLogger(ChatService.class).warn("Unable to persist RAG trace {}", traceId);
        }
    }

    private static String errorCategory(RuntimeException exception) {
        if (exception instanceof org.springframework.ai.retry.NonTransientAiException) {
            return "PROVIDER_REQUEST_REJECTED";
        }
        if (exception instanceof org.springframework.ai.retry.TransientAiException) {
            return "PROVIDER_UNAVAILABLE";
        }
        if (exception instanceof org.springframework.dao.DataAccessException) {
            return "RETRIEVAL_STORAGE_ERROR";
        }
        if (exception instanceof IllegalArgumentException) {
            return "INVALID_QUERY";
        }
        return "INTERNAL_REQUEST_ERROR";
    }

    private static long toMillis(long nanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private String providerName() {
        return generator.providerName();
    }

    private String activeModelId() {
        return generator.modelId();
    }

    private static final class RequestProgress {
        private com.acme.rag.retrieval.RetrievalResult retrievalResult;
        private long retrievalNanos;
        private long generationNanos;
        private int contextCount;
    }
}
