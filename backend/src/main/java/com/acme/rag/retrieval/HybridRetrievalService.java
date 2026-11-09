package com.acme.rag.retrieval;

import com.acme.rag.security.CurrentIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Performs role-scoped vector, keyword, and exact-identifier retrieval before fusion and evidence gating. */
@Service
public class HybridRetrievalService {
    private final EmbeddingModel embeddings;
    private final HybridRetrievalRepository repository;
    private final ExactIdentifierExtractor identifiers;
    private final ReciprocalRankFusion fusion;
    private final CurrentIdentity identity;
    private final EvidenceSufficiencyGate sufficiencyGate;
    private final QueryPlanningService queryPlanning;
    private final int candidateDepth;
    private final int maximumLexicalQueries;
    private final int maximumAlternativeQueries;

    public HybridRetrievalService(
            EmbeddingModel embeddings,
            HybridRetrievalRepository repository,
            ExactIdentifierExtractor identifiers,
            ReciprocalRankFusion fusion,
            CurrentIdentity identity,
            EvidenceSufficiencyGate sufficiencyGate,
            QueryPlanningService queryPlanning,
            @Value("${app.retrieval.candidate-depth:10}") int candidateDepth,
            @Value("${app.query-planner.max-lexical-queries:4}") int maximumLexicalQueries,
            @Value("${app.query-planner.max-alternative-queries:3}") int maximumAlternativeQueries) {
        this.embeddings = embeddings;
        this.repository = repository;
        this.identifiers = identifiers;
        this.fusion = fusion;
        this.identity = identity;
        this.sufficiencyGate = sufficiencyGate;
        this.queryPlanning = queryPlanning;
        this.candidateDepth = candidateDepth;
        this.maximumLexicalQueries = maximumLexicalQueries;
        this.maximumAlternativeQueries = maximumAlternativeQueries;
    }

    /** Retrieves using roles resolved from the authenticated server-side identity. */
    public RetrievalResult retrieve(String message) {
        return retrieve(message, identity.allowedRoles());
    }

    /**
     * Retrieves for an offline evaluation identity. Expected document IDs affect gate diagnostics/policy only;
     * database ACL predicates still constrain every candidate before fusion.
     */
    public RetrievalResult retrieveForEvaluation(String message, Set<String> roles) {
        return retrieveForEvaluation(message, roles, Set.of());
    }

    /** Evaluation variant that records expected-document diagnostics without bypassing role filtering. */
    public RetrievalResult retrieveForEvaluation(String message, Set<String> roles, Set<String> expectedDocumentIds) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("Evaluation identity has no retrieval scope");
        }
        return retrieve(message, Set.copyOf(roles), Set.copyOf(expectedDocumentIds), true);
    }

    private RetrievalResult retrieve(String message, Set<String> roles) {
        return retrieve(message, roles, Set.of());
    }

    private RetrievalResult retrieve(String message, Set<String> roles, Set<String> expectedDocumentIds) {
        return retrieve(message, roles, expectedDocumentIds, false);
    }

    private RetrievalResult retrieve(String message, Set<String> roles, Set<String> expectedDocumentIds,
                                     boolean deterministicPlanning) {
        String query = normalize(message);
        QueryPlanningService.PlannedQuery planned = deterministicPlanning
                ? queryPlanning.planDeterministically(query) : queryPlanning.plan(query);
        List<RetrievalCandidate> vectorRows = new ArrayList<>();
        List<RetrievalCandidate> keywordRows = new ArrayList<>();
        List<RetrievalCandidate> exactRows = new ArrayList<>();
        java.util.Map<String, Integer> retrievalCounts = new java.util.LinkedHashMap<>();
        List<QueryPlanningService.QueryVariant> variants = planned.retrievalVariants(
                maximumLexicalQueries, maximumAlternativeQueries);
        List<QueryPlanningService.QueryVariant> vectorVariants = variants.stream()
                .filter(variant -> variant.channels().contains(QueryPlanningService.Channel.VECTOR)).toList();
        List<float[]> queryVectors = embeddings.embed(vectorVariants.stream()
                .map(QueryPlanningService.QueryVariant::text).toList());
        java.util.Map<String, float[]> vectorsByQuery = new java.util.HashMap<>();
        for (int index = 0; index < vectorVariants.size(); index++) {
            vectorsByQuery.put(vectorVariants.get(index).text(), queryVectors.get(index));
        }
        int variantIndex = 0;
        for (QueryPlanningService.QueryVariant variant : variants) {
            if (variant.channels().contains(QueryPlanningService.Channel.VECTOR)) {
                List<RetrievalCandidate> rows = repository.vector(vectorsByQuery.get(variant.text()), roles, candidateDepth);
                vectorRows.addAll(rows);
                retrievalCounts.put("q" + variantIndex + "_vector", rows.size());
            }
            if (variant.channels().contains(QueryPlanningService.Channel.KEYWORD)) {
                List<RetrievalCandidate> rows = repository.keyword(variant.text(), roles, candidateDepth);
                keywordRows.addAll(rows);
                retrievalCounts.put("q" + variantIndex + "_keyword", rows.size());
            }
            if (variant.channels().contains(QueryPlanningService.Channel.EXACT)) {
                int count = 0;
                for (String identifier : identifiers.extract(variant.text())) {
                    List<RetrievalCandidate> rows = repository.exact(identifier, roles, candidateDepth);
                    exactRows.addAll(rows);
                    count += rows.size();
                }
                retrievalCounts.put("q" + variantIndex + "_exact", count);
            }
            variantIndex++;
        }
        List<RetrievalCandidate> vectorCandidates = bestPerChunk(vectorRows);
        List<RetrievalCandidate> keywordCandidates = bestPerChunk(keywordRows);
        List<RetrievalCandidate> exactCandidates = bestPerChunk(exactRows);
        List<RankedChunk> fused = fusion.fuse(List.of(limit(vectorCandidates), limit(keywordCandidates),
                limit(exactCandidates)));
        retrievalCounts = retrievalCounts.entrySet().stream().limit(30).collect(java.util.stream.Collectors.toMap(
                java.util.Map.Entry::getKey, java.util.Map.Entry::getValue, (first, ignored) -> first,
                java.util.LinkedHashMap::new));
        QueryPlanningDiagnostics diagnostics = planned.diagnostics().withRetrieval(
                java.util.Map.of("vector", vectorCandidates.size(), "keyword", keywordCandidates.size(),
                        "exact", exactCandidates.size()), fused.size(), retrievalCounts);
        // Planner output may broaden retrieval terms, never the deterministic safety/evidence decision.
        EvidenceSufficiencyGate.Decision decision = sufficiencyGate.evaluateForPlannedQuery(
                planned.original(), evidenceQuery(planned.plan()),
                fused, expectedDocumentIds, roles);
        return new RetrievalResult(decision.chunks(), vectorCandidates.size(), keywordCandidates.size(),
                exactCandidates.size(), decision.sufficient(), decision.reason(), fused.size(), fused,
                decision.diagnostic(), diagnostics);
    }

    private static List<RetrievalCandidate> bestPerChunk(List<RetrievalCandidate> rows) {
        java.util.Map<String, RetrievalCandidate> best = new java.util.LinkedHashMap<>();
        for (RetrievalCandidate candidate : rows) {
            RetrievalCandidate existing = best.get(candidate.chunkId());
            if (existing == null || candidate.score() > existing.score()) {
                best.put(candidate.chunkId(), candidate);
            } else if (candidate.lexicalCoverage() > existing.lexicalCoverage()) {
                best.put(candidate.chunkId(), new RetrievalCandidate(existing.chunkId(), existing.versionId(),
                        existing.documentId(), existing.title(), existing.version(), existing.section(), existing.text(),
                        existing.score(), existing.vectorMatch(), existing.keywordMatch(), existing.exactMatch(),
                        candidate.lexicalCoverage()));
            }
        }
        return best.values().stream().sorted(java.util.Comparator.comparingDouble(RetrievalCandidate::score).reversed()
                .thenComparing(RetrievalCandidate::chunkId)).toList();
    }

    private List<RetrievalCandidate> limit(List<RetrievalCandidate> rows) {
        return rows.stream().limit(candidateDepth).toList();
    }

    /** Retains normalized mixed-script identifiers for deterministic evidence coverage. */
    private static String evidenceQuery(QueryPlan plan) {
        return String.join(" ", plan.subject(), String.join(" ", plan.constraints()));
    }

    private static String normalize(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Message must not be blank");
        }
        String normalized = message.strip().replaceAll("\\s+", " ");
        if (normalized.length() > 2000) {
            throw new IllegalArgumentException("Message exceeds 2000 characters");
        }
        return normalized;
    }
}
