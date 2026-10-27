package com.acme.rag.generation;

import com.acme.rag.citation.Citation;
import java.util.List;

/** Chat result; citations identify authorized supplied sources, not verified factual entailments. */
public record ChatResponse(String answer, List<Citation> citations, String traceId, String groundingStatus) {
    public ChatResponse {
        citations = List.copyOf(citations);
    }
}
