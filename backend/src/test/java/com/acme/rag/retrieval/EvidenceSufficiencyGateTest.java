package com.acme.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidenceSufficiencyGateTest {
    private final EvidenceSufficiencyGate gate = new EvidenceSufficiencyGate(0.35, 0.3);

    @Test
    void diagnosesExpectedVectorMatchRejectedOnlyForMissingKeywordSignal() {
        RankedChunk expected = chunk("doc-policy", 0.63, 0.0, false, 0.2);

        EvidenceSufficiencyGate.Decision decision = gate.evaluate(
                "Which response header contains the request identifier?", List.of(expected), Set.of("doc-policy"));

        assertThat(decision.sufficient()).isFalse();
        assertThat(decision.reason()).isEqualTo("NO_KEYWORD_MATCH");
        assertThat(decision.diagnostic().bestExpectedVectorSimilarity()).isEqualTo(0.63);
        assertThat(decision.diagnostic().bestExpectedKeywordScore()).isZero();
        assertThat(decision.diagnostic().ablationDecisions())
                .containsEntry("CURRENT_COVERAGE", false)
                .containsEntry("VECTOR_ONLY", true)
                .containsEntry("LEXICAL_COVERAGE_ONLY", false)
                .containsEntry("COVERAGE_030", false)
                .containsEntry("COVERAGE_040", false);
    }

    @Test
    void reportsThresholdPassingCountsWithoutIncludingCandidateText() {
        RankedChunk strong = chunk("doc-policy", 0.63, 0.4, false, 0.5);
        RankedChunk lexicalOnly = chunk("doc-other", 0.20, 0.4, false, 0.5);

        EvidenceSufficiencyGate.Decision decision = gate.evaluate("policy", List.of(strong, lexicalOnly), Set.of("doc-policy"));

        assertThat(decision.sufficient()).isTrue();
        assertThat(decision.diagnostic().vectorPassingCandidateCount()).isEqualTo(1);
        assertThat(decision.diagnostic().keywordPassingCandidateCount()).isEqualTo(2);
        assertThat(decision.diagnostic().jointPassingCandidateCount()).isEqualTo(1);
        assertThat(decision.diagnostic().bestExpectedVectorSimilarity()).isEqualTo(0.63);
        assertThat(decision.diagnostic().ablationDecisions()).containsEntry("CURRENT_COVERAGE", true);
    }

    @Test
    void identifiesSplitSignalsAndDoesNotCombineAcrossDifferentChunks() {
        RankedChunk vector = chunk("doc-vector", 0.8, 0.0, false);
        RankedChunk keyword = chunk("doc-keyword", 0.1, 0.4, false);

        EvidenceSufficiencyGate.Decision decision = gate.evaluate("policy", List.of(vector, keyword));

        assertThat(decision.sufficient()).isFalse();
        assertThat(decision.reason()).isEqualTo("SPLIT_SIGNALS_NO_SINGLE_CANDIDATE");
    }

    @Test
    void lexicalCoveragePreventsIncidentalWordMatchFromAdmittingHardNegative() {
        RankedChunk incidental = new RankedChunk("faq", UUID.randomUUID(), "doc-public", "FAQ", 1,
                "General", "Acme Enterprise offers an onboarding support portal.", 0.02,
                0.64, 0.2, false, 0.1);

        EvidenceSufficiencyGate.Decision decision = gate.evaluate(
                "What is Acme's confidential acquisition price?", List.of(incidental));

        assertThat(decision.sufficient()).isFalse();
        assertThat(decision.reason()).isEqualTo("SENSITIVE_REQUEST");
    }

    @Test
    void lexicalCoveragePassesParaphrasedFactWhenNoSingleKeywordQueryConjunctionExists() {
        RankedChunk leave = new RankedChunk("leave", UUID.randomUUID(), "doc-hr-leave-policy", "Leave", 1,
                "Policy", "Employees get 20 days of annual leave per calendar year.", 0.02,
                0.80, 0.9, false, 0.66);

        EvidenceSufficiencyGate.Decision decision = gate.evaluate(
                "How many annual leave days do employees receive?", List.of(leave));

        assertThat(decision.sufficient()).isTrue();
        assertThat(decision.reason()).isEqualTo("LEXICAL_AND_RELEVANCE_MATCH");
    }

    @Test
    void policyGuardsFailClosedForFutureContractorAndCrossDepartmentRequests() {
        RankedChunk candidate = new RankedChunk("leave", UUID.randomUUID(), "doc-hr-leave-policy", "Leave", 1,
                "Policy", "Employees get 20 days of annual leave per calendar year.", 0.02,
                0.86, 0.9, false, 0.83);

        assertThat(gate.evaluate("What leave policy will Acme adopt in 2030?", List.of(candidate)).reason())
                .isEqualTo("UNSUPPORTED_FUTURE_SPECULATION");
        assertThat(gate.evaluate("What leave benefits do contractors receive?", List.of(candidate)).reason())
                .isEqualTo("CONTRACTOR_POLICY_UNAVAILABLE");
        assertThat(gate.evaluate("How many annual leave days do employees receive?", List.of(candidate),
                Set.of("doc-hr-leave-policy"), Set.of("ENGINEERING", "PUBLIC")).reason())
                .isEqualTo("REQUIRED_HR_SCOPE_UNAVAILABLE");
    }

    @Test
    void asksAboutSecurityPolicyWithoutRequestingSecretValueRemainAnswerable() {
        RankedChunk policy = new RankedChunk("policy", UUID.randomUUID(), "doc-security", "Security Policy", 1,
                "Secrets", "Secrets must be stored in the managed vault and rotated quarterly.", 0.02,
                0.78, 0.8, false, 0.6);

        EvidenceSufficiencyGate.Decision decision = gate.evaluate(
                "What is the policy for storing secrets?", List.of(policy));

        assertThat(decision.sufficient()).isTrue();
    }

    @Test
    void requestForSecretValueRemainsFailClosedDespiteStrongAuthorizedCandidate() {
        RankedChunk policy = new RankedChunk("policy", UUID.randomUUID(), "doc-security", "Security Policy", 1,
                "Secrets", "Secrets must be stored in the managed vault and rotated quarterly.", 0.02,
                0.88, 0.9, false, 0.8);

        EvidenceSufficiencyGate.Decision decision = gate.evaluate(
                "What is the production signing secret value?", List.of(policy));

        assertThat(decision.sufficient()).isFalse();
        assertThat(decision.reason()).isEqualTo("SENSITIVE_REQUEST");
    }

    @Test
    void policyGuidanceMentioningSecretsIsNotTreatedAsSecretValueExfiltration() {
        RankedChunk policy = new RankedChunk("policy", UUID.randomUUID(), "doc-security", "Security Policy", 1,
                "Secrets", "Secrets must be stored in the managed vault and rotated quarterly.", 0.02,
                0.78, 0.8, false, 0.6);

        EvidenceSufficiencyGate.Decision decision = gate.evaluate(
                "What is the policy for storing secrets?", List.of(policy));

        assertThat(decision.sufficient()).isTrue();
    }

    @Test
    void requestForSensitiveValueRemainsFailClosedWithStrongLexicalAndVectorSignals() {
        RankedChunk policy = new RankedChunk("policy", UUID.randomUUID(), "doc-security", "Security Policy", 1,
                "Secrets", "Secrets must be stored in the managed vault and rotated quarterly.", 0.02,
                0.88, 0.9, false, 0.8);

        EvidenceSufficiencyGate.Decision decision = gate.evaluate(
                "What is the production signing secret value?", List.of(policy));

        assertThat(decision.sufficient()).isFalse();
        assertThat(decision.reason()).isEqualTo("SENSITIVE_REQUEST");
    }

    @Test
    void validMultiIdentifierQuestionIsNotRejectedAsAnUnmatchedSingleIdentifier() {
        RankedChunk exact = new RankedChunk("gateway", UUID.randomUUID(), "doc-engineering-api-architecture",
                "API Architecture", 1, "Routing", "The gateway routes /v1 requests to Workspace.",
                0.02, 0.8, 0.7, false, 0.8);

        EvidenceSufficiencyGate.Decision decision = gate.evaluate(
                "How do the gateway and Workspace service interact?", List.of(exact));

        assertThat(decision.reason()).isNotEqualTo("UNMATCHED_TECHNICAL_IDENTIFIER");
    }

    @Test
    void shorterIdentifierDoesNotTreatLongerIdentifierAsAnExactMatch() {
        RankedChunk longerIdentifier = new RankedChunk("incident", UUID.randomUUID(), "doc-security", "Incident", 1,
                "Exercise", "The restricted incident is IR-204.", 0.01, 0.1, 0.0, true, 0.0);

        EvidenceSufficiencyGate.Decision decision = gate.evaluate("What happened in IR-20?", List.of(longerIdentifier));

        assertThat(decision.sufficient()).isFalse();
        assertThat(decision.reason()).isNotEqualTo("EXACT_IDENTIFIER_MATCH");
        assertThat(decision.chunks()).isEmpty();
    }

    @Test
    void terminalSentencePeriodDoesNotPreventExactIdentifierMatch() {
        RankedChunk terminalIdentifier = new RankedChunk("incident", UUID.randomUUID(), "doc-security", "Incident", 1,
                "Exercise", "The restricted incident is IR-204.", 0.01, 0.1, 0.0, true, 0.0);

        EvidenceSufficiencyGate.Decision decision = gate.evaluate("What happened in IR-204.", List.of(terminalIdentifier));

        assertThat(decision.sufficient()).isTrue();
        assertThat(decision.reason()).isEqualTo("EXACT_IDENTIFIER_MATCH");
        assertThat(decision.chunks()).containsExactly(terminalIdentifier);
    }

    @Test
    void releaseThresholdsExposeFalseAdmissionRisk() {
        assertThat(EvidenceSufficiencyGate.Diagnostic.releaseThresholdsMet(0.1, 0.05, 0)).isTrue();
        assertThat(EvidenceSufficiencyGate.Diagnostic.releaseThresholdsMet(0.1, 0.06, 0)).isFalse();
        assertThat(EvidenceSufficiencyGate.Diagnostic.releaseThresholdsMet(0.1, 0.0, 1)).isFalse();
    }

    private static RankedChunk chunk(String id, double vector, double keyword, boolean exact) {
        return new RankedChunk(id, UUID.randomUUID(), id, "Title", 1, "Section", "diagnostic text", 0.01,
                vector, keyword, exact);
    }

    private static RankedChunk chunk(String id, double vector, double keyword, boolean exact, double coverage) {
        return new RankedChunk(id, UUID.randomUUID(), id, "Title", 1, "Section", "diagnostic text", 0.01,
                vector, keyword, exact, coverage);
    }
}
