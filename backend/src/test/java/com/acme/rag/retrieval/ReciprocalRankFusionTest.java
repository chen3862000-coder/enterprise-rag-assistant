package com.acme.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTest {
    @Test
    void fusesByReciprocalRankAndDeduplicatesChunkIds() {
        ReciprocalRankFusion fusion = new ReciprocalRankFusion(60, 5);
        RetrievalCandidate shared = candidate("shared");
        RetrievalCandidate vectorOnly = candidate("vector");
        RetrievalCandidate keywordOnly = candidate("keyword");

        List<RankedChunk> result = fusion.fuse(List.of(
                List.of(shared, vectorOnly),
                List.of(keywordOnly, shared)));

        assertThat(result).extracting(RankedChunk::chunkId).containsExactly("shared", "keyword", "vector");
    }

    private static RetrievalCandidate candidate(String id) {
        return new RetrievalCandidate(id, UUID.randomUUID(), "doc-public", "Title", 1, "Section", "evidence", 1.0, 0, 0, 0);
    }
}
