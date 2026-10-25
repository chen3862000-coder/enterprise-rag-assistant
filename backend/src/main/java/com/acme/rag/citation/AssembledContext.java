package com.acme.rag.citation;

import java.util.List;

public record AssembledContext(String promptContext, List<CitationEvidence> evidence) {
    public AssembledContext {
        evidence = List.copyOf(evidence);
    }
}
