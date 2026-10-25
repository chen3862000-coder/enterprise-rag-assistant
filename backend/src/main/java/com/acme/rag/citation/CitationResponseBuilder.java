package com.acme.rag.citation;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class CitationResponseBuilder {
    private static final Pattern CITATION_TOKEN = Pattern.compile("\\[?\\bS[0-9]+\\b\\]?");
    private static final String ABSTENTION = "I could not verify this answer with authorized source evidence.";
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
            return new ValidatedAnswer(ABSTENTION, citations, "INSUFFICIENT_EVIDENCE");
        }
        return new ValidatedAnswer(sanitized.toString().strip(), citations, "SUPPORTED");
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
