package com.acme.rag.generation;

import com.acme.rag.citation.Citation;
import java.util.List;

/** Chat result; citations identify authorized supplied sources, not verified factual entailments. */
public record ChatResponse(String answer, List<Citation> citations, String traceId, String groundingStatus,
                           com.acme.rag.retrieval.QueryIntent queryIntent) {
    public ChatResponse {
        citations = List.copyOf(citations);
    }

    public ChatResponse(String answer, List<Citation> citations, String traceId, String groundingStatus) {
        this(answer, citations, traceId, groundingStatus, com.acme.rag.retrieval.QueryIntent.UNKNOWN);
    }
}
