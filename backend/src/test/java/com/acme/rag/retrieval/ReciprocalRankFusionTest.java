package com.acme.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.acme.rag.retrieval.EvidenceSufficiencyGate;

class ReciprocalRankFusionTest {
    @Test
    void fusesByReciprocalRankAndDeduplicatesChunkIds() {
        ReciprocalRankFusion fusion = new ReciprocalRankFusion(60, 5);
        RetrievalCandidate shared = candidate("shared", true, true);
        RetrievalCandidate vectorOnly = candidate("vector", true, false);
        RetrievalCandidate keywordOnly = candidate("keyword", false, true);

        List<RankedChunk> result = fusion.fuse(List.of(
                List.of(shared, vectorOnly),
                List.of(keywordOnly, shared)));

        assertThat(result).extracting(RankedChunk::chunkId).containsExactly("shared", "keyword", "vector");
        assertThat(result.get(0).keywordScore()).isPositive();
    }

    @Test
    void weakVectorOnlyCandidatesDoNotPassEvidenceGate() {
        EvidenceSufficiencyGate gate = new EvidenceSufficiencyGate(0.72, 0.05);
        RankedChunk weak = new RankedChunk("weak", UUID.randomUUID(), "doc-public", "Public FAQ", 1,
                "General", "general support information", 0.01, 0.61, 0.0, false);

        var decision = gate.evaluate("What is our acquisition price?", List.of(weak));

        assertThat(decision.sufficient()).isFalse();
        assertThat(decision.reason()).isEqualTo("NO_STRONG_EVIDENCE");
        assertThat(decision.chunks()).isEmpty();
    }

    @Test
    void sensitiveQueryDoesNotPassExactIdentifierBypassBasedOnWordCooccurrence() {
        EvidenceSufficiencyGate gate = new EvidenceSufficiencyGate(0.72, 0.05);
        RankedChunk report = new RankedChunk("incident", UUID.randomUUID(), "doc-incident", "Incident Report", 1,
                "IR-204", "Exercise IR-204 tested escalation timing only and contained no credentials.",
                0.01, 0.4, 0.0, true);

        var decision = gate.evaluate("What exact production credential was exposed during IR-204?", List.of(report));

        assertThat(decision.sufficient()).isFalse();
        assertThat(decision.reason()).isEqualTo("NO_STRONG_EVIDENCE");
    }

    @Test
    void exactTechnicalIdentifierPassesEvidenceGateWithoutGenericKeywordScore() {
        EvidenceSufficiencyGate gate = new EvidenceSufficiencyGate(0.72, 0.05);
        RankedChunk exact = new RankedChunk("error", UUID.randomUUID(), "doc-errors", "Errors", 1,
                "GW-204", "GW-204 indicates upstream connection failure", 0.01, 0.4, 0.0, true);

        var decision = gate.evaluate("What does GW-204 mean?", List.of(exact));

        assertThat(decision.sufficient()).isTrue();
        assertThat(decision.reason()).isEqualTo("EXACT_IDENTIFIER_MATCH");
    }

    @Test
    void exactIncidentIdDoesNotAnswerSensitiveFactMissingFromIncidentText() {
        EvidenceSufficiencyGate gate = new EvidenceSufficiencyGate(0.72, 0.05);
        RankedChunk report = new RankedChunk("incident", UUID.randomUUID(), "doc-incident", "Incident Report", 1,
                "IR-204", "Exercise IR-204 tested escalation timing only and contained no credentials.",
                0.02, 0.4, 0.0, true);

        var decision = gate.evaluate("What exact production credential was exposed during IR-204?", List.of(report));

        assertThat(decision.sufficient()).isFalse();
        assertThat(decision.reason()).isEqualTo("NO_STRONG_EVIDENCE");
        assertThat(decision.chunks()).isEmpty();
    }

    @Test
    void multipleIdentifierExactMatchesDoNotAssertAllRequestedFactsArePresent() {
        EvidenceSufficiencyGate gate = new EvidenceSufficiencyGate(0.72, 0.05);
        RankedChunk exact = new RankedChunk("error", UUID.randomUUID(), "doc-errors", "Errors", 1,
                "GW-204", "GW-204 indicates an upstream connection failure", 0.01, 0.4, 0.0, true);

        var decision = gate.evaluate("Compare GW-204 with API-429 behavior", List.of(exact));

        assertThat(decision.sufficient()).isFalse();
        assertThat(decision.reason()).isEqualTo("NO_STRONG_EVIDENCE");
    }

    private static RetrievalCandidate candidate(String id, boolean vector, boolean keyword) {
        return new RetrievalCandidate(id, UUID.randomUUID(), "doc-public", "Title", 1, "Section", "evidence", 0.9,
                vector, keyword, false);
    }
}
