package com.acme.rag.citation;

import com.acme.rag.security.CurrentIdentity;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class CitationAccessValidator {
    private final SourceRepository sources;
    private final CurrentIdentity identity;

    public CitationAccessValidator(SourceRepository sources, CurrentIdentity identity) {
        this.sources = sources;
        this.identity = identity;
    }

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
                    "I could not verify this answer with currently authorized source evidence.", citations, "INSUFFICIENT_EVIDENCE");
        }
        return new CitationResponseBuilder.ValidatedAnswer(answer.answer(), citations, "SUPPORTED");
    }
}
