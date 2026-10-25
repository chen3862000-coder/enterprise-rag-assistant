package com.acme.rag.retrieval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class ExactIdentifierExtractor {
    private static final Pattern IDENTIFIER = Pattern.compile("(?U)(?=[\\p{Alnum}_./:-]*[0-9])[\\p{Alnum}_./:-]{2,}");

    public List<String> extract(String query) {
        Matcher matcher = IDENTIFIER.matcher(query);
        Set<String> identifiers = new LinkedHashSet<>();
        while (matcher.find()) {
            String identifier = matcher.group().toLowerCase(Locale.ROOT);
            if (identifier.length() <= 64) {
                identifiers.add(identifier);
            }
        }
        return List.copyOf(identifiers);
    }
}
