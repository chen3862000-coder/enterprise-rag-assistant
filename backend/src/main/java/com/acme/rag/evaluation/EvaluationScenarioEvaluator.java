package com.acme.rag.evaluation;

import com.acme.rag.citation.CitationValidator;
import com.acme.rag.retrieval.HybridRetrievalService;
import com.acme.rag.retrieval.RetrievalResult;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.acme.rag.citation.CitationEvidence;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public final class EvaluationScenarioEvaluator {
    private static final int RANK_CUTOFF = 5;
    private final HybridRetrievalService retrieval;
    private final CitationValidator citations;
    private final DemoRoleResolver roles;

    public EvaluationScenarioEvaluator(
            HybridRetrievalService retrieval,
            CitationValidator citations,
            DemoRoleResolver roles) {
        this.retrieval = retrieval;
        this.citations = citations;
        this.roles = roles;
    }

    public EvaluationScenarioResult evaluate(EvaluationScenario scenario) {
        RetrievalResult retrieved = retrieval.retrieveForEvaluation(scenario.query(), roles.rolesFor(scenario.user()));
        List<String> documentIds = retrieved.chunks().stream().map(chunk -> chunk.documentId()).distinct().toList();
        Set<String> retrievedSet = new HashSet<>(documentIds);
        List<String> missing = scenario.expectedDocumentIds().stream().filter(id -> !retrievedSet.contains(id)).toList();
        List<String> forbidden = scenario.mustNotRetrieveDocumentIds().stream().filter(retrievedSet::contains).toList();
        double recall = scenario.expectedDocumentIds().isEmpty() ? 0.0 : recallAtFive(
                scenario.expectedDocumentIds(), retrieved.chunks().stream().limit(RANK_CUTOFF)
                        .map(chunk -> chunk.documentId()).toList());
        double reciprocalRank = reciprocalRank(scenario.expectedDocumentIds(), documentIds);
        Map<String, Integer> versions = retrieved.chunks().stream().collect(Collectors.toMap(
                chunk -> chunk.documentId(), chunk -> chunk.version(), (first, ignored) -> first));
        boolean freshnessCorrect = freshnessCorrect(scenario, versions);
        List<CitationEvidence> supplied = new java.util.ArrayList<>();
        StringBuilder deterministicCitationCheck = new StringBuilder();
        for (int index = 0; index < Math.min(RANK_CUTOFF, retrieved.chunks().size()); index++) {
            String citationId = "S" + (index + 1);
            supplied.add(new CitationEvidence(citationId, retrieved.chunks().get(index)));
            deterministicCitationCheck.append('[').append(citationId).append("] ");
        }
        List<CitationEvidence> validCitations = citations.cited(deterministicCitationCheck.toString(), supplied);
        boolean validGeneratedIds = citationIdsValid(deterministicCitationCheck.toString(), supplied, validCitations);
        boolean invalidIdRejected = citations.cited("[S999]", supplied).isEmpty();
        boolean citationCorrect = validGeneratedIds && invalidIdRejected;
        boolean expectedEvidenceFound = !scenario.expectedDocumentIds().isEmpty() && missing.isEmpty();
        boolean abstentionCorrect = abstentionCorrect(scenario, expectedEvidenceFound);
        boolean leakage = !forbidden.isEmpty();
        boolean retrievalCorrect = scenario.expectedDocumentIds().isEmpty() || missing.isEmpty();
        boolean passed = retrievalCorrect && !leakage && freshnessCorrect && citationCorrect && abstentionCorrect;
        return new EvaluationScenarioResult(scenario.id(), scenario.category(), scenario.expectedDocumentIds(),
                documentIds, missing, forbidden, scenario.expectedVersion(), versions, reciprocalRank, recall,
                leakage, freshnessCorrect, citationCorrect, abstentionCorrect, passed);
    }

    private static boolean hasFreshnessExpectation(EvaluationScenario scenario) {
        return scenario.expectedVersion() != null;
    }

    private static boolean freshnessCorrect(EvaluationScenario scenario, Map<String, Integer> versions) {
        return !hasFreshnessExpectation(scenario) || scenario.expectedDocumentIds().stream()
                .allMatch(id -> versions.containsKey(id) && versions.get(id) >= scenario.expectedVersion());
    }

    private static double recallAtFive(List<String> expected, List<String> ranked) {
        if (expected.isEmpty()) {
            return 1.0;
        }
        Set<String> found = ranked.stream().limit(RANK_CUTOFF).collect(Collectors.toSet());
        long count = expected.stream().filter(found::contains).count();
        return (double) count / expected.size();
    }

    private static double reciprocalRank(List<String> expected, List<String> ranked) {
        if (expected.isEmpty()) {
            return 1.0;
        }
        for (int index = 0; index < ranked.size(); index++) {
            if (expected.contains(ranked.get(index))) {
                return 1.0 / (index + 1);
            }
        }
        return 0.0;
    }

    private static boolean citationIdsValid(String answer, List<CitationEvidence> supplied, List<CitationEvidence> cited) {
        Set<String> suppliedIds = supplied.stream().map(CitationEvidence::citationId).collect(Collectors.toSet());
        Set<String> citedIds = cited.stream().map(CitationEvidence::citationId).collect(Collectors.toSet());
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[?\\bS[0-9]+\\b\\]?").matcher(answer);
        while (matcher.find()) {
            String id = matcher.group().replace("[", "").replace("]", "");
            if (!suppliedIds.contains(id)) {
                return false;
            }
        }
        return citedIds.stream().allMatch(suppliedIds::contains);
    }

    private static boolean abstentionCorrect(EvaluationScenario scenario, boolean expectedEvidenceFound) {
        boolean deterministicAbstention = !expectedEvidenceFound;
        if (scenario.abstentionExpected()) {
            return deterministicAbstention;
        }
        return Boolean.TRUE.equals(scenario.shouldNotAbstain()) ? !deterministicAbstention : true;
    }
}
