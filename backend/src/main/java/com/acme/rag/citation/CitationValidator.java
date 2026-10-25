package com.acme.rag.citation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class CitationValidator {
    private static final Pattern CITATION = Pattern.compile("\\bS[1-9][0-9]*\\b");

    public List<CitationEvidence> cited(String answer, List<CitationEvidence> supplied) {
        Set<String> validIds = supplied.stream().map(CitationEvidence::citationId).collect(java.util.stream.Collectors.toSet());
        Set<String> returnedIds = new LinkedHashSet<>();
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            if (validIds.contains(matcher.group())) {
                returnedIds.add(matcher.group());
            }
        }
        return supplied.stream().filter(item -> returnedIds.contains(item.citationId())).toList();
    }
}
