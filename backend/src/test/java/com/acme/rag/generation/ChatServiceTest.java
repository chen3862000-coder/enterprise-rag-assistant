package com.acme.rag.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.rag.citation.Citation;
import com.acme.rag.citation.CitationAccessValidator;
import com.acme.rag.citation.CitationResponseBuilder;
import com.acme.rag.citation.ContextAssembler;
import com.acme.rag.citation.SourceRepository;
import com.acme.rag.citation.SourcePreview;
import com.acme.rag.observability.RagMetrics;
import com.acme.rag.observability.RagTraceRepository;
import com.acme.rag.retrieval.EvidenceSufficiencyGate;
import com.acme.rag.retrieval.HybridRetrievalService;
import com.acme.rag.retrieval.RankedChunk;
import com.acme.rag.retrieval.RetrievalResult;
import com.acme.rag.security.CurrentIdentity;
import com.acme.rag.security.DemoIdentity;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ChatServiceTest {
    private final HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
    private final AnswerGenerator generator;
    private final SourceRepository sources = mock(SourceRepository.class);
    private final CitationAccessValidator citationAccess;
    private final RagTraceRepository traces = mock(RagTraceRepository.class);
    private final RagMetrics metrics = new RagMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    private final CurrentIdentity identity;
    private final ChatService service;

    ChatServiceTest() {
        var identities = mock(com.acme.rag.security.DemoIdentityService.class);
        when(identities.find("alice")).thenReturn(java.util.Optional.of(
                new DemoIdentity("alice", Set.of("PUBLIC", "ENGINEERING"), false)));
        identity = new CurrentIdentity(identities, mock(ApplicationEventPublisher.class));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "", List.of()));
        var modelProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(modelProvider.getIfAvailable()).thenReturn(null);
        generator = new AnswerGenerator(modelProvider, "none", "unused");
        citationAccess = new CitationAccessValidator(sources, identity);
        service = new ChatService(retrieval, new ContextAssembler(12000), generator,
                new CitationResponseBuilder(new com.acme.rag.citation.CitationValidator()), citationAccess,
                traces, metrics, identity);
    }

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void modelFreePassingGateReturnsAuthorizedCitationsWithRetrievalOnlyStatus() {
        RankedChunk chunk = chunk();
        when(retrieval.retrieve("What is the API limit?")).thenReturn(result(List.of(chunk), true));
        when(sources.findAuthorized(chunk.chunkId(), chunk.documentId(), Set.of("PUBLIC", "ENGINEERING")))
                .thenReturn(java.util.Optional.of(new SourcePreview(chunk.chunkId(), chunk.documentId(), chunk.title(),
                        chunk.version(), chunk.section(), chunk.text())));

        ChatResponse response = service.chat("What is the API limit?");

        assertThat(response.groundingStatus()).isEqualTo("RETRIEVAL_ONLY");
        assertThat(response.answer()).contains("Retrieval-only mode");
        assertThat(response.citations()).containsExactly(new Citation("S1", chunk.documentId(), chunk.title(),
                chunk.version(), chunk.chunkId(), chunk.section()));
        verify(traces).save(any(), any());
    }

    @Test
    void modelFreeFailedGateReturnsNoCitationsAndDoesNotReauthorize() {
        when(retrieval.retrieve("What is the secret value?")).thenReturn(result(List.of(chunk()), false));

        ChatResponse response = service.chat("What is the secret value?");

        assertThat(response.groundingStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(response.citations()).isEmpty();
        verify(traces).save(any(), any());
        verify(sources, never()).findAuthorized(any(), anySet());
    }

    @Test
    void retrievalFailureIsRecordedAndPropagatedAsServiceFailure() {
        org.springframework.dao.DataAccessResourceFailureException failure =
                new org.springframework.dao.DataAccessResourceFailureException("database unavailable");
        when(retrieval.retrieve("What is the API limit?")).thenThrow(failure);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.chat("What is the API limit?"))
                .isInstanceOf(ChatServiceException.class)
                .hasMessage("Document storage is temporarily unavailable")
                .hasCause(failure);

        org.mockito.ArgumentCaptor<com.acme.rag.observability.RagTrace> trace =
                org.mockito.ArgumentCaptor.forClass(com.acme.rag.observability.RagTrace.class);
        verify(traces).save(trace.capture(), any());
        assertThat(trace.getValue().status()).isEqualTo("FAILED");
        assertThat(trace.getValue().groundingStatus()).isEqualTo("REQUEST_FAILED");
    }

    @Test
    void invalidQueryRemainsAClientErrorInsteadOfEvidenceAbstention() {
        when(retrieval.retrieve("bad query")).thenThrow(new IllegalArgumentException("invalid query detail"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.chat("bad query"))
                .isInstanceOf(ChatServiceException.class)
                .hasMessage("The chat request is invalid");
    }

    private static RankedChunk chunk() {
        return new RankedChunk("doc-policy-v1-c0", UUID.randomUUID(), "doc-policy", "Policy", 1,
                "Limits", "The Enterprise API limit is 1000 per minute.", 0.01, 0.8, 0.5, false);
    }

    private static RetrievalResult result(List<RankedChunk> chunks, boolean sufficient) {
        return new RetrievalResult(sufficient ? chunks : List.of(), 1, 1, 0, sufficient,
                sufficient ? "LEXICAL_AND_RELEVANCE_MATCH" : "SENSITIVE_REQUEST", chunks.size(), chunks,
                EvidenceSufficiencyGate.Diagnostic.empty(chunks.size()));
    }
}
