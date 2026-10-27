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
    private final int candidateDepth;

    public HybridRetrievalService(
            EmbeddingModel embeddings,
            HybridRetrievalRepository repository,
            ExactIdentifierExtractor identifiers,
            ReciprocalRankFusion fusion,
            CurrentIdentity identity,
            EvidenceSufficiencyGate sufficiencyGate,
            @Value("${app.retrieval.candidate-depth:10}") int candidateDepth) {
        this.embeddings = embeddings;
        this.repository = repository;
        this.identifiers = identifiers;
        this.fusion = fusion;
        this.identity = identity;
        this.sufficiencyGate = sufficiencyGate;
        this.candidateDepth = candidateDepth;
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
        return retrieve(message, Set.copyOf(roles), Set.copyOf(expectedDocumentIds));
    }

    private RetrievalResult retrieve(String message, Set<String> roles) {
        return retrieve(message, roles, Set.of());
    }

    private RetrievalResult retrieve(String message, Set<String> roles, Set<String> expectedDocumentIds) {
        String query = normalize(message);
        float[] vector = embeddings.embed(query);
        List<RetrievalCandidate> vectorCandidates = repository.vector(vector, roles, candidateDepth);
        List<RetrievalCandidate> keywordCandidates = repository.keyword(query, roles, candidateDepth);
        List<RetrievalCandidate> exactCandidates = new ArrayList<>();
        for (String identifier : identifiers.extract(query)) {
            exactCandidates.addAll(repository.exact(identifier, roles, candidateDepth));
        }
        List<RankedChunk> fused = fusion.fuse(List.of(vectorCandidates, keywordCandidates, exactCandidates));
        EvidenceSufficiencyGate.Decision decision = sufficiencyGate.evaluate(query, fused, expectedDocumentIds, roles);
        return new RetrievalResult(decision.chunks(), vectorCandidates.size(), keywordCandidates.size(),
                exactCandidates.size(), decision.sufficient(), decision.reason(), fused.size(), fused,
                decision.diagnostic());
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
