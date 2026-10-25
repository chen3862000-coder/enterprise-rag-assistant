package com.acme.rag.citation;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.rag.retrieval.RankedChunk;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CitationValidatorTest {
    @Test
    void validatesSuppliedCitationIdsButDoesNotAssertClaimEntailment() {
        CitationEvidence first = evidence("S1", "chunk-1");
        CitationEvidence second = evidence("S2", "chunk-2");

        List<CitationEvidence> result = new CitationValidator().cited("Supported [S2], invalid [S9].", List.of(first, second));

        assertThat(result).containsExactly(second);
    }

    @Test
    void responseFallsBackWhenModelReturnsNoValidCitations() {
        CitationEvidence first = evidence("S1", "chunk-1");

        var result = new CitationResponseBuilder(new CitationValidator()).validate("Claim without evidence [S9].", List.of(first));

        assertThat(result.answer()).contains("no answer claim was validated");
        assertThat(result.citations()).isEmpty();
        assertThat(result.groundingStatus()).isEqualTo("CITATION_IDS_UNAVAILABLE");
    }

    @Test
    void responseKeepsAlreadyBracketedValidCitationOnce() {
        CitationEvidence first = evidence("S1", "chunk-1");

        var result = new CitationResponseBuilder(new CitationValidator()).validate(
                "Generation is disabled. Inspect evidence [S1].", List.of(first));

        assertThat(result.answer()).isEqualTo("Generation is disabled. Inspect evidence [S1].");
        assertThat(result.citations()).hasSize(1);
    }

    private static CitationEvidence evidence(String id, String chunkId) {
        return new CitationEvidence(id, new RankedChunk(chunkId, UUID.randomUUID(), "doc", "Title", 1,
                "Section", "text", 1.0, 0.9, 0.5, false));
    }
}
