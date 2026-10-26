package com.acme.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidenceSufficiencyGateTest {
    private final EvidenceSufficiencyGate gate = new EvidenceSufficiencyGate(0.35, 0.0005);

    @Test
    void diagnosesExpectedVectorMatchRejectedOnlyForMissingKeywordSignal() {
        RankedChunk expected = chunk("doc-policy", 0.63, 0.0, false);

        EvidenceSufficiencyGate.Decision decision = gate.evaluate(
                "Which response header contains the request identifier?", List.of(expected), Set.of("doc-policy"));

        assertThat(decision.sufficient()).isFalse();
        assertThat(decision.reason()).isEqualTo("NO_KEYWORD_MATCH");
        assertThat(decision.diagnostic().bestExpectedVectorSimilarity()).isEqualTo(0.63);
        assertThat(decision.diagnostic().bestExpectedKeywordScore()).isZero();
        assertThat(decision.diagnostic().ablationDecisions())
                .containsEntry("CURRENT_AND", false)
                .containsEntry("VECTOR_ONLY", true)
                .containsEntry("KEYWORD_ONLY", false)
                .containsEntry("RELAXED_AND", false);
    }

    @Test
    void reportsThresholdPassingCountsWithoutIncludingCandidateText() {
        RankedChunk strong = chunk("doc-policy", 0.63, 0.01, false);
        RankedChunk lexicalOnly = chunk("doc-other", 0.20, 0.01, false);

        EvidenceSufficiencyGate.Decision decision = gate.evaluate("policy", List.of(strong, lexicalOnly), Set.of("doc-policy"));

        assertThat(decision.sufficient()).isTrue();
        assertThat(decision.diagnostic().vectorPassingCandidateCount()).isEqualTo(1);
        assertThat(decision.diagnostic().keywordPassingCandidateCount()).isEqualTo(2);
        assertThat(decision.diagnostic().jointPassingCandidateCount()).isEqualTo(1);
        assertThat(decision.diagnostic().bestExpectedVectorSimilarity()).isEqualTo(0.63);
        assertThat(decision.diagnostic().ablationDecisions()).containsEntry("CURRENT_AND", true);
    }

    @Test
    void identifiesSplitSignalsAndDoesNotCombineAcrossDifferentChunks() {
        RankedChunk vector = chunk("doc-vector", 0.8, 0.0, false);
        RankedChunk keyword = chunk("doc-keyword", 0.1, 0.01, false);

        EvidenceSufficiencyGate.Decision decision = gate.evaluate("policy", List.of(vector, keyword));

        assertThat(decision.sufficient()).isFalse();
        assertThat(decision.reason()).isEqualTo("SPLIT_SIGNALS_NO_SINGLE_CANDIDATE");
    }

    private static RankedChunk chunk(String id, double vector, double keyword, boolean exact) {
        return new RankedChunk(id, UUID.randomUUID(), id, "Title", 1, "Section", "diagnostic text", 0.01,
                vector, keyword, exact);
    }
}
