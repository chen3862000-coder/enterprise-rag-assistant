package com.acme.rag.citation;

import com.acme.rag.security.CurrentIdentity;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Rechecks citation authorization and active-version identity immediately before returning a response. */
@Component
public final class CitationAccessValidator {
    private final SourceRepository sources;
    private final CurrentIdentity identity;

    public CitationAccessValidator(SourceRepository sources, CurrentIdentity identity) {
        this.sources = sources;
        this.identity = identity;
    }

    /**
     * Reauthorizes every citation in an answer; if any citation is missing, unauthorized, or stale,
     * returns an abstention rather than the original answer.
     */
    public CitationResponseBuilder.ValidatedAnswer reauthorize(CitationResponseBuilder.ValidatedAnswer answer) {
        Set<String> allowedChunks = new HashSet<>();
        answer.citations().forEach(citation -> sources.findAuthorized(citation.chunkId(), citation.documentId(), identity.allowedRoles())
                .filter(source -> source.documentId().equals(citation.documentId())
                        && source.version() == citation.version())
                .ifPresent(source -> allowedChunks.add(source.chunkId())));
        List<Citation> citations = answer.citations().stream()
                .filter(citation -> allowedChunks.contains(citation.chunkId())).toList();
        if (citations.isEmpty() || citations.size() != answer.citations().size()) {
            return new CitationResponseBuilder.ValidatedAnswer(
                    "I could not find sufficient currently authorized source evidence to answer that question.", citations, "INSUFFICIENT_EVIDENCE");
        }
        return new CitationResponseBuilder.ValidatedAnswer(answer.answer(), citations, answer.groundingStatus());
    }

    /** Returns only citations that still resolve to the same authorized active document version. */
    public List<Citation> reauthorize(List<Citation> citations) {
        Set<String> allowedChunks = new HashSet<>();
        citations.forEach(citation -> sources.findAuthorized(citation.chunkId(), citation.documentId(), identity.allowedRoles())
                .filter(source -> source.documentId().equals(citation.documentId())
                        && source.version() == citation.version())
                .ifPresent(source -> allowedChunks.add(source.chunkId())));
        return citations.stream().filter(citation -> allowedChunks.contains(citation.chunkId())).toList();
    }
}
