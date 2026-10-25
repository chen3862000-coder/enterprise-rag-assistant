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
    private final String modelId;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Injected Spring service collaborators are private and not exposed")
    public ChatService(
            HybridRetrievalService retrieval,
            ContextAssembler contextAssembler,
            AnswerGenerator generator,
            CitationResponseBuilder citations,
            CitationAccessValidator citationAccess,
            RagTraceRepository traces,
            RagMetrics metrics,
            CurrentIdentity identity,
            @org.springframework.beans.factory.annotation.Value("${spring.ai.deepseek.chat.model:deepseek-chat}") String modelId) {
        this.retrieval = retrieval;
        this.contextAssembler = contextAssembler;
        this.generator = generator;
        this.citations = citations;
        this.citationAccess = citationAccess;
        this.traces = traces;
        this.metrics = metrics;
        this.identity = identity;
        this.modelId = modelId;
    }

    public ChatResponse chat(String message) {
        long startedNanos = System.nanoTime();
        Instant createdAt = Instant.now();
        metrics.request();
        var principal = identity.require();
        long retrievalStarted = System.nanoTime();
        var retrievalResult = retrieval.retrieve(message);
        long retrievalNanos = System.nanoTime() - retrievalStarted;
        metrics.retrieval(retrievalNanos);
        if (retrievalResult.chunks().isEmpty()) {
            metrics.emptyRetrieval();
        }
        var context = contextAssembler.assemble(retrievalResult.chunks());
        long generationStarted = System.nanoTime();
        String answer = generator.generate(message, context);
        if (citations.hasInvalidCitations(answer, context.evidence())) {
            metrics.invalidCitation();
        }
        var validated = citationAccess.reauthorize(citations.validate(answer, context.evidence()));
        long generationNanos = System.nanoTime() - generationStarted;
        metrics.generation(generationNanos);
        long totalNanos = System.nanoTime() - startedNanos;
        String traceId = UUID.randomUUID().toString();
        try {
            traces.save(new RagTrace(UUID.fromString(traceId), principal.username(), toMillis(retrievalNanos),
                    toMillis(generationNanos), toMillis(totalNanos), retrievalResult.vectorCandidates(),
                    retrievalResult.keywordCandidates(), retrievalResult.exactCandidates(), context.evidence().size(),
                    validated.citations().size(), "deepseek", modelId, validated.groundingStatus(), "SUCCEEDED", null,
                    createdAt), ContentHasher.sha256(message.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (org.springframework.dao.DataAccessException exception) {
            org.slf4j.LoggerFactory.getLogger(ChatService.class).warn("Unable to persist RAG trace {}", traceId);
        }
        return new ChatResponse(validated.answer(), validated.citations(), traceId, validated.groundingStatus());
    }

    private static long toMillis(long nanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos);
    }
}
