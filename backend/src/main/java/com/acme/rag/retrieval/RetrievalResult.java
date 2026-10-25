package com.acme.rag.retrieval;

import java.util.List;

public record RetrievalResult(List<RankedChunk> chunks, int vectorCandidates, int keywordCandidates, int exactCandidates) {
    public RetrievalResult {
        chunks = List.copyOf(chunks);
    }
}
