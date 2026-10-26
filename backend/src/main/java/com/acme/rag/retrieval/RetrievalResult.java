package com.acme.rag.retrieval;

import java.util.List;

public record RetrievalResult(List<RankedChunk> chunks, int vectorCandidates, int keywordCandidates, int exactCandidates,
                              boolean evidenceSufficient, String gateReason, int candidateCount,
                              List<RankedChunk> retrievedCandidates,
                              EvidenceSufficiencyGate.Diagnostic diagnostic) {
    public RetrievalResult {
        chunks = List.copyOf(chunks);
        retrievedCandidates = List.copyOf(retrievedCandidates);
    }

    public RetrievalResult(List<RankedChunk> chunks, int vectorCandidates, int keywordCandidates, int exactCandidates,
                           boolean evidenceSufficient, String gateReason, int candidateCount,
                           List<RankedChunk> retrievedCandidates) {
        this(chunks, vectorCandidates, keywordCandidates, exactCandidates, evidenceSufficient, gateReason,
                candidateCount, retrievedCandidates, EvidenceSufficiencyGate.Diagnostic.empty(candidateCount));
    }
}
