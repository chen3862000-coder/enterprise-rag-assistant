package com.acme.rag.retrieval;

import java.util.List;

public record RetrievalResult(List<RankedChunk> chunks, int vectorCandidates, int keywordCandidates, int exactCandidates,
                              boolean evidenceSufficient, String gateReason, int candidateCount,
                              List<RankedChunk> retrievedCandidates) {
    public RetrievalResult {
        chunks = List.copyOf(chunks);
        retrievedCandidates = List.copyOf(retrievedCandidates);
    }
}
