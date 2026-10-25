package com.acme.rag.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acme.rag.citation.CitationValidator;
import com.acme.rag.citation.CitationResponseBuilder;
import com.acme.rag.retrieval.HybridRetrievalService;
import com.acme.rag.retrieval.RankedChunk;
import com.acme.rag.retrieval.RetrievalResult;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvaluationScenarioEvaluatorTest {
    @Test
    void deterministicallyScoresRetrievalGateAndCitationIdChecksWithoutChatModel() {
        HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
        RankedChunk exactChunk = new RankedChunk("error", UUID.randomUUID(), "doc-errors", "Errors", 2,
                "GW-204", "GW-204 means an upstream connection failure", 0.01, 0.4, 0.0, true);
        when(retrieval.retrieveForEvaluation("exact error GW-204", Set.of("ENGINEERING", "PUBLIC")))
                .thenReturn(result(List.of(exactChunk), true, "EXACT_IDENTIFIER_MATCH"));
        DemoRoleResolver roleResolver = mock(DemoRoleResolver.class);
        when(roleResolver.rolesFor("alice")).thenReturn(Set.of("ENGINEERING", "PUBLIC"));
        EvaluationScenario scenario = new EvaluationScenario("r1", EvaluationCategory.KEYWORD_EXACT_MATCH, "alice",
                "exact error GW-204", List.of("doc-errors"), List.of("doc-hr"), 2, true, false, null);

        EvaluationScenarioResult result = new EvaluationScenarioEvaluator(
                retrieval, new CitationResponseBuilder(new CitationValidator()), roleResolver).evaluate(scenario);

        assertThat(result.recallAt5()).isEqualTo(1.0);
        assertThat(result.reciprocalRank()).isEqualTo(1.0);
        assertThat(result.aclLeakage()).isFalse();
        assertThat(result.freshnessCorrect()).isTrue();
        assertThat(result.citationIdValidationCorrect()).isTrue();
        assertThat(result.evidenceGateCorrect()).isTrue();
        assertThat(result.passed()).isTrue();
    }

    @Test
    void recordsForbiddenCandidateAsAclLeakageFailure() {
        HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
        when(retrieval.retrieveForEvaluation("leave policy", Set.of("ENGINEERING")))
                .thenReturn(result(List.of(chunk("doc-hr", 1)), false, "NO_STRONG_EVIDENCE"));
        DemoRoleResolver roleResolver = mock(DemoRoleResolver.class);
        when(roleResolver.rolesFor("alice")).thenReturn(Set.of("ENGINEERING"));
        EvaluationScenario scenario = new EvaluationScenario("r2", EvaluationCategory.PERMISSION_ISOLATION, "alice",
                "leave policy", List.of(), List.of("doc-hr"), null, false, true, null);

        EvaluationScenarioResult result = new EvaluationScenarioEvaluator(
                retrieval, new CitationResponseBuilder(new CitationValidator()), roleResolver).evaluate(scenario);

        assertThat(result.aclLeakage()).isTrue();
        assertThat(result.forbiddenDocumentIds()).containsExactly("doc-hr");
        assertThat(result.passed()).isFalse();
    }

    @Test
    void skipsFreshnessCheckWhenScenarioDoesNotSpecifyExpectedVersion() {
        HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
        when(retrieval.retrieveForEvaluation("known policy", Set.of("ENGINEERING")))
                .thenReturn(result(List.of(chunk("doc-policy", 4)), true, "LEXICAL_AND_RELEVANCE_MATCH"));
        DemoRoleResolver roleResolver = mock(DemoRoleResolver.class);
        when(roleResolver.rolesFor("alice")).thenReturn(Set.of("ENGINEERING"));
        EvaluationScenario scenario = new EvaluationScenario("r3", EvaluationCategory.FACT_LOOKUP, "alice",
                "known policy", List.of("doc-policy"), List.of(), null, true, false, null);

        EvaluationScenarioResult result = new EvaluationScenarioEvaluator(
                retrieval, new CitationResponseBuilder(new CitationValidator()), roleResolver).evaluate(scenario);

        assertThat(result.freshnessCorrect()).isTrue();
        assertThat(result.passed()).isTrue();
    }

    @Test
    void comparesFreshnessOnlyForScenarioWithExpectedActiveVersion() {
        HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
        when(retrieval.retrieveForEvaluation("current policy", Set.of("ENGINEERING")))
                .thenReturn(result(List.of(chunk("doc-policy", 4)), true, "LEXICAL_AND_RELEVANCE_MATCH"));
        DemoRoleResolver roleResolver = mock(DemoRoleResolver.class);
        when(roleResolver.rolesFor("alice")).thenReturn(Set.of("ENGINEERING"));
        EvaluationScenario scenario = new EvaluationScenario("r4", EvaluationCategory.DOCUMENT_FRESHNESS, "alice",
                "current policy", List.of("doc-policy"), List.of(), 2, true, false, null);

        EvaluationScenarioResult result = new EvaluationScenarioEvaluator(
                retrieval, new CitationResponseBuilder(new CitationValidator()), roleResolver).evaluate(scenario);

        assertThat(result.freshnessCorrect()).isTrue();
        assertThat(result.passed()).isTrue();
    }

    @Test
    void hardNegativeWeakAuthorizedCandidateUsesSharedGateAndPassesAbstentionExpectation() {
        HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
        RankedChunk weakChunk = new RankedChunk("public-chunk", UUID.randomUUID(), "doc-public", "Public FAQ",
                1, "FAQ", "The product has a standard support portal and onboarding information.", 0.012, 0.61, 0.0, false);
        when(retrieval.retrieveForEvaluation("What confidential acquisition price did Acme agree to?", Set.of("ENGINEERING", "PUBLIC")))
                .thenReturn(new RetrievalResult(List.of(), 1, 0, 0, false, "NO_STRONG_EVIDENCE", 1,
                        List.of(weakChunk)));
        DemoRoleResolver roleResolver = mock(DemoRoleResolver.class);
        when(roleResolver.rolesFor("alice")).thenReturn(Set.of("ENGINEERING", "PUBLIC"));
        EvaluationScenario scenario = new EvaluationScenario("hard-negative", EvaluationCategory.HARD_NEGATIVE,
                "alice", "What confidential acquisition price did Acme agree to?", List.of(), List.of(), null, false, true, null);

        EvaluationScenarioResult result = new EvaluationScenarioEvaluator(
                retrieval, new CitationResponseBuilder(new CitationValidator()), roleResolver).evaluate(scenario);

        assertThat(result.retrievedDocumentIds()).containsExactly("doc-public");
        assertThat(result.gateReason()).isEqualTo("NO_STRONG_EVIDENCE");
        assertThat(result.evidenceGateCorrect()).isTrue();
        assertThat(result.passed()).isTrue();
    }

    @Test
    void hardNegativeEvaluatorDoesNotInferGateSuccessFromFixtureExpectation() {
        HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
        when(retrieval.retrieveForEvaluation("unknown price", Set.of("ENGINEERING")))
                .thenReturn(result(List.of(chunk("doc-public", 1)), true, "LEXICAL_AND_RELEVANCE_MATCH"));
        DemoRoleResolver roleResolver = mock(DemoRoleResolver.class);
        when(roleResolver.rolesFor("alice")).thenReturn(Set.of("ENGINEERING"));
        EvaluationScenario scenario = new EvaluationScenario("gate-failure", EvaluationCategory.HARD_NEGATIVE,
                "alice", "unknown price", List.of(), List.of(), null, false, true, null);

        EvaluationScenarioResult result = new EvaluationScenarioEvaluator(
                retrieval, new CitationResponseBuilder(new CitationValidator()), roleResolver).evaluate(scenario);

        assertThat(result.evidenceGateCorrect()).isFalse();
        assertThat(result.gateReason()).isEqualTo("LEXICAL_AND_RELEVANCE_MATCH");
        assertThat(result.passed()).isFalse();
    }

    @Test
    void permissionIsolationUnanswerableScenarioRequiresActualGateRejection() {
        HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
        RankedChunk weakChunk = new RankedChunk("public-chunk", UUID.randomUUID(), "doc-public", "Public FAQ",
                1, "FAQ", "general help information", 0.01, 0.60, 0.0, false);
        when(retrieval.retrieveForEvaluation("leave allowance", Set.of("ENGINEERING", "PUBLIC")))
                .thenReturn(new RetrievalResult(List.of(), 1, 0, 0, false, "NO_STRONG_EVIDENCE", 1,
                        List.of(weakChunk)));
        DemoRoleResolver roleResolver = mock(DemoRoleResolver.class);
        when(roleResolver.rolesFor("alice")).thenReturn(Set.of("ENGINEERING", "PUBLIC"));
        EvaluationScenario scenario = new EvaluationScenario("permission-weak", EvaluationCategory.PERMISSION_ISOLATION,
                "alice", "leave allowance", List.of(), List.of("doc-hr"), null, false, true, null);

        EvaluationScenarioResult result = new EvaluationScenarioEvaluator(
                retrieval, new CitationResponseBuilder(new CitationValidator()), roleResolver).evaluate(scenario);

        assertThat(result.evidenceGateCorrect()).isTrue();
        assertThat(result.gateReason()).isEqualTo("NO_STRONG_EVIDENCE");
        assertThat(result.passed()).isTrue();
    }

    @Test
    void unanswerableScenarioFailsIfProductionGateReturnsWeakCandidatesAsSufficient() {
        HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
        when(retrieval.retrieveForEvaluation("unknown price", Set.of("ENGINEERING")))
                .thenReturn(result(List.of(chunk("doc-public", 1)), true, "LEXICAL_AND_RELEVANCE_MATCH"));
        DemoRoleResolver roleResolver = mock(DemoRoleResolver.class);
        when(roleResolver.rolesFor("alice")).thenReturn(Set.of("ENGINEERING"));
        EvaluationScenario scenario = new EvaluationScenario("unanswerable-weak", EvaluationCategory.UNANSWERABLE,
                "alice", "unknown price", List.of(), List.of(), null, false, true, null);

        EvaluationScenarioResult result = new EvaluationScenarioEvaluator(
                retrieval, new CitationResponseBuilder(new CitationValidator()), roleResolver).evaluate(scenario);

        assertThat(result.evidenceGateCorrect()).isFalse();
        assertThat(result.passed()).isFalse();
    }

    @Test
    void falseAbstentionFailsGateMetricAndKeepsRetrievalRecallSeparate() {
        HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
        RankedChunk candidate = chunk("doc-policy", 1);
        when(retrieval.retrieveForEvaluation("known policy", Set.of("ENGINEERING")))
                .thenReturn(new RetrievalResult(List.of(), 1, 0, 0, false, "NO_STRONG_EVIDENCE", 1,
                        List.of(candidate)));
        DemoRoleResolver roleResolver = mock(DemoRoleResolver.class);
        when(roleResolver.rolesFor("alice")).thenReturn(Set.of("ENGINEERING"));
        EvaluationScenario scenario = new EvaluationScenario("false-abstention", EvaluationCategory.FACT_LOOKUP,
                "alice", "known policy", List.of("doc-policy"), List.of(), null, true, false, null);

        EvaluationScenarioResult result = new EvaluationScenarioEvaluator(
                retrieval, new CitationResponseBuilder(new CitationValidator()), roleResolver).evaluate(scenario);

        assertThat(result.recallAt5()).isEqualTo(1.0);
        assertThat(result.missingDocumentIds()).isEmpty();
        assertThat(result.evidenceGateCorrect()).isFalse();
        assertThat(result.passed()).isFalse();
    }

    private static RankedChunk chunk(String documentId, int version) {
        return new RankedChunk(documentId + "-chunk", UUID.randomUUID(), documentId, "Title", version,
                "Section", "safe evidence", 1.0, 0.9, 0.5, false);
    }

    private static RetrievalResult result(List<RankedChunk> chunks, boolean sufficient, String reason) {
        return new RetrievalResult(chunks, 1, 1, 0, sufficient, reason, chunks.size(), chunks);
    }
}
