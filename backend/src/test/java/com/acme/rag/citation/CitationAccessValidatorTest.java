package com.acme.rag.citation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acme.rag.security.CurrentIdentity;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CitationAccessValidatorTest {
    @Test
    void removesCitationWhenAccessWasRevokedBeforeResponse() {
        SourceRepository repository = mock(SourceRepository.class);
        CurrentIdentity identity = mock(CurrentIdentity.class);
        when(identity.allowedRoles()).thenReturn(Set.of("ENGINEERING"));
        when(repository.findAuthorized("chunk", Set.of("ENGINEERING"))).thenReturn(Optional.empty());
        Citation citation = new Citation("S1", "doc", "Policy", 1, "chunk", "Section");
        var answer = new CitationResponseBuilder.ValidatedAnswer("Claim [S1]", List.of(citation), "CITATIONS_PRESENT");

        var checked = new CitationAccessValidator(repository, identity).reauthorize(answer);

        assertThat(checked.citations()).isEmpty();
        assertThat(checked.groundingStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(checked.answer()).contains("currently authorized");
    }

    @Test
    void removesCitationWhenActiveVersionChanged() {
        SourceRepository repository = mock(SourceRepository.class);
        CurrentIdentity identity = mock(CurrentIdentity.class);
        when(identity.allowedRoles()).thenReturn(Set.of("ENGINEERING"));
        when(repository.findAuthorized("chunk", Set.of("ENGINEERING"))).thenReturn(Optional.of(
                new SourcePreview("chunk", "doc", "Policy", 2, "Section", "new version")));
        Citation citation = new Citation("S1", "doc", "Policy", 1, "chunk", "Section");
        var answer = new CitationResponseBuilder.ValidatedAnswer("Claim [S1]", List.of(citation), "CITATIONS_PRESENT");

        var checked = new CitationAccessValidator(repository, identity).reauthorize(answer);

        assertThat(checked.citations()).isEmpty();
        assertThat(checked.groundingStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    @Test
    void abstainsIfAnyCitedEvidenceWasRevokedAfterGeneration() {
        SourceRepository repository = mock(SourceRepository.class);
        CurrentIdentity identity = mock(CurrentIdentity.class);
        when(identity.allowedRoles()).thenReturn(Set.of("ENGINEERING"));
        when(repository.findAuthorized("chunk-1", Set.of("ENGINEERING"))).thenReturn(Optional.of(
                new SourcePreview("chunk-1", "doc-1", "Guide", 1, "One", "visible")));
        when(repository.findAuthorized("chunk-2", Set.of("ENGINEERING"))).thenReturn(Optional.empty());
        List<Citation> citations = List.of(
                new Citation("S1", "doc-1", "Guide", 1, "chunk-1", "One"),
                new Citation("S2", "doc-2", "Restricted", 1, "chunk-2", "Two"));

        var checked = new CitationAccessValidator(repository, identity).reauthorize(
                new CitationResponseBuilder.ValidatedAnswer("Combined claim [S1] [S2]", citations, "CITATIONS_PRESENT"));

        assertThat(checked.citations()).isEmpty();
        assertThat(checked.answer()).contains("sufficient currently authorized source evidence");
        assertThat(checked.groundingStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }
}
