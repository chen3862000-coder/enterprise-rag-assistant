package com.acme.rag.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acme.rag.citation.CitationValidator;
import com.acme.rag.retrieval.HybridRetrievalService;
import com.acme.rag.retrieval.RankedChunk;
import com.acme.rag.retrieval.RetrievalResult;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvaluationScenarioEvaluatorTest {
    @Test
    void deterministicallyScoresRetrievalWithoutCallingChatModel() {
        HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
        when(retrieval.retrieveForEvaluation("exact error GW-204", Set.of("ENGINEERING", "PUBLIC")))
                .thenReturn(new RetrievalResult(List.of(chunk("doc-errors", 2)), 1, 1, 1));
        DemoRoleResolver roleResolver = mock(DemoRoleResolver.class);
        when(roleResolver.rolesFor("alice")).thenReturn(Set.of("ENGINEERING", "PUBLIC"));
        EvaluationScenario scenario = new EvaluationScenario("r1", EvaluationCategory.KEYWORD_EXACT_MATCH, "alice",
                "exact error GW-204", List.of("doc-errors"), List.of("doc-hr"), 2, false, null);

        EvaluationScenarioResult result = new EvaluationScenarioEvaluator(
                retrieval, new CitationValidator(), roleResolver).evaluate(scenario);

        assertThat(result.recallAt5()).isEqualTo(1.0);
        assertThat(result.reciprocalRank()).isEqualTo(1.0);
        assertThat(result.aclLeakage()).isFalse();
        assertThat(result.freshnessCorrect()).isTrue();
        assertThat(result.citationLogicCorrect()).isTrue();
        assertThat(result.abstentionCorrect()).isTrue();
        assertThat(result.passed()).isTrue();
    }

    @Test
    void recordsForbiddenCandidateAsAclLeakageFailure() {
        HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
        when(retrieval.retrieveForEvaluation("leave policy", Set.of("ENGINEERING")))
                .thenReturn(new RetrievalResult(List.of(chunk("doc-hr", 1)), 0, 1, 0));
        DemoRoleResolver roleResolver = mock(DemoRoleResolver.class);
        when(roleResolver.rolesFor("alice")).thenReturn(Set.of("ENGINEERING"));
        EvaluationScenario scenario = new EvaluationScenario("r2", EvaluationCategory.PERMISSION_ISOLATION, "alice",
                "leave policy", List.of(), List.of("doc-hr"), null, true, null);

        EvaluationScenarioResult result = new EvaluationScenarioEvaluator(
                retrieval, new CitationValidator(), roleResolver).evaluate(scenario);

        assertThat(result.aclLeakage()).isTrue();
        assertThat(result.forbiddenDocumentIds()).containsExactly("doc-hr");
        assertThat(result.passed()).isFalse();
    }

    @Test
    void skipsFreshnessCheckWhenScenarioDoesNotSpecifyExpectedVersion() {
        HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
        when(retrieval.retrieveForEvaluation("known policy", Set.of("ENGINEERING")))
                .thenReturn(new RetrievalResult(List.of(chunk("doc-policy", 4)), 1, 1, 0));
        DemoRoleResolver roleResolver = mock(DemoRoleResolver.class);
        when(roleResolver.rolesFor("alice")).thenReturn(Set.of("ENGINEERING"));
        EvaluationScenario scenario = new EvaluationScenario("r3", EvaluationCategory.FACT_LOOKUP, "alice",
                "known policy", List.of("doc-policy"), List.of(), null, false, null);

        EvaluationScenarioResult result = new EvaluationScenarioEvaluator(
                retrieval, new CitationValidator(), roleResolver).evaluate(scenario);

        assertThat(result.freshnessCorrect()).isTrue();
        assertThat(result.passed()).isTrue();
    }

    @Test
    void comparesFreshnessOnlyForScenarioWithExpectedActiveVersion() {
        HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
        when(retrieval.retrieveForEvaluation("current policy", Set.of("ENGINEERING")))
                .thenReturn(new RetrievalResult(List.of(chunk("doc-policy", 4)), 1, 1, 0));
        DemoRoleResolver roleResolver = mock(DemoRoleResolver.class);
        when(roleResolver.rolesFor("alice")).thenReturn(Set.of("ENGINEERING"));
        EvaluationScenario scenario = new EvaluationScenario("r4", EvaluationCategory.DOCUMENT_FRESHNESS, "alice",
                "current policy", List.of("doc-policy"), List.of(), 2, false, null);

        EvaluationScenarioResult result = new EvaluationScenarioEvaluator(
                retrieval, new CitationValidator(), roleResolver).evaluate(scenario);

        assertThat(result.freshnessCorrect()).isTrue();
        assertThat(result.passed()).isTrue();
    }

    private static RankedChunk chunk(String documentId, int version) {
        return new RankedChunk(documentId + "-chunk", UUID.randomUUID(), documentId, "Title", version, "Section", "safe evidence", 1.0);
    }
}
