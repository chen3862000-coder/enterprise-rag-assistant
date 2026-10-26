package com.acme.rag.citation;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class CitationResponseBuilder {
    private static final Pattern CITATION_TOKEN = Pattern.compile("\\[?\\bS[0-9]+\\b\\]?");
    private static final String ABSTENTION = "Citations identify supplied sources only; no answer claim was validated.";
    private final CitationValidator validator;

    public CitationResponseBuilder(CitationValidator validator) {
        this.validator = validator;
    }

    public ValidatedAnswer validate(String answer, List<CitationEvidence> supplied) {
        List<CitationEvidence> cited = validator.cited(answer, supplied);
        Map<String, CitationEvidence> byId = cited.stream().collect(java.util.stream.Collectors.toMap(
                CitationEvidence::citationId, evidence -> evidence));
        Matcher matcher = CITATION_TOKEN.matcher(answer);
        StringBuilder sanitized = new StringBuilder();
        while (matcher.find()) {
            String id = matcher.group().replace("[", "").replace("]", "");
            matcher.appendReplacement(sanitized, Matcher.quoteReplacement(byId.containsKey(id) ? "[" + id + "]" : ""));
        }
        matcher.appendTail(sanitized);
        List<Citation> citations = cited.stream().map(this::toCitation).toList();
        if (citations.isEmpty()) {
            return new ValidatedAnswer(ABSTENTION, citations, "CITATION_IDS_UNAVAILABLE");
        }
        return new ValidatedAnswer(sanitized.toString().strip(), citations, "CITATIONS_PRESENT");
    }

    public ValidatedAnswer retrievalOnly(List<CitationEvidence> supplied) {
        List<Citation> citations = supplied.stream().map(this::toCitation).toList();
        if (citations.isEmpty()) {
            return new ValidatedAnswer("Insufficient authorized evidence was found.", List.of(),
                    "INSUFFICIENT_EVIDENCE");
        }
        return new ValidatedAnswer("Retrieval-only mode: answer generation is disabled. Review these authorized sources.",
                citations, "RETRIEVAL_ONLY");
    }

    public boolean hasInvalidCitations(String answer, List<CitationEvidence> supplied) {
        java.util.Set<String> validIds = supplied.stream().map(CitationEvidence::citationId)
                .collect(java.util.stream.Collectors.toSet());
        Matcher matcher = CITATION_TOKEN.matcher(answer);
        while (matcher.find()) {
            String id = matcher.group().replace("[", "").replace("]", "");
            if (!validIds.contains(id)) {
                return true;
            }
        }
        return false;
    }

    public String stripUnsupportedCitationClaims(String answer, List<CitationEvidence> supplied) {
        java.util.Set<String> suppliedIds = supplied.stream().map(CitationEvidence::citationId)
                .collect(java.util.stream.Collectors.toSet());
        Matcher matcher = CITATION_TOKEN.matcher(answer);
        StringBuilder sanitized = new StringBuilder();
        while (matcher.find()) {
            String id = matcher.group().replace("[", "").replace("]", "");
            matcher.appendReplacement(sanitized,
                    Matcher.quoteReplacement(suppliedIds.contains(id) ? "[" + id + "]" : ""));
        }
        matcher.appendTail(sanitized);
        return sanitized.toString().strip();
    }

    private Citation toCitation(CitationEvidence evidence) {
        var chunk = evidence.chunk();
        return new Citation(evidence.citationId(), chunk.documentId(), chunk.title(), chunk.version(), chunk.chunkId(), chunk.section());
    }

    public record ValidatedAnswer(String answer, List<Citation> citations, String groundingStatus) {
        public ValidatedAnswer {
            citations = List.copyOf(citations);
        }
    }
}
