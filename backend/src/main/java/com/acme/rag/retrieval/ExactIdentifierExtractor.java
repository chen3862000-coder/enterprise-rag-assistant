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
    private static final Pattern IDENTIFIER = Pattern.compile("(?=[A-Za-z0-9_./:-]*[0-9])[A-Za-z0-9_./:-]{2,}");
    private static final Pattern API_SYMBOL = Pattern.compile("\\b[A-Z][A-Z0-9_]{5,}\\b");
    private static final Pattern HEADER_IDENTIFIER = Pattern.compile("\\bX-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)+\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASCII_IDENTIFIER = Pattern.compile("(?<![A-Za-z0-9_])[A-Za-z][A-Za-z0-9_]{2,}(?![A-Za-z0-9_])");
    private static final Pattern CJK = Pattern.compile("[\\u3040-\\u30ff\\u3400-\\u9fff\\uac00-\\ud7af]");
    private static final Pattern CJK_RUN = Pattern.compile("[\\u3040-\\u30ff\\u3400-\\u9fff\\uac00-\\ud7af]+");

    public List<String> extract(String query) {
        Matcher matcher = IDENTIFIER.matcher(query);
        Set<String> identifiers = new LinkedHashSet<>();
        while (matcher.find()) {
            String identifier = matcher.group().replaceFirst("\\.+$", "").toLowerCase(Locale.ROOT);
            if (identifier.length() <= 64) {
                identifiers.add(identifier);
            }
        }
        Matcher apiSymbol = API_SYMBOL.matcher(query);
        while (apiSymbol.find()) {
            identifiers.add(apiSymbol.group().toLowerCase(Locale.ROOT));
        }
        Matcher header = HEADER_IDENTIFIER.matcher(query);
        while (header.find()) {
            identifiers.add(header.group().toLowerCase(Locale.ROOT));
        }
        if (CJK.matcher(query).find()) {
            String mixedQuery = CJK_RUN.matcher(java.text.Normalizer.normalize(query,
                    java.text.Normalizer.Form.NFKC)).replaceAll(" ");
            Matcher ascii = ASCII_IDENTIFIER.matcher(mixedQuery);
            while (ascii.find()) {
                identifiers.add(ascii.group().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(identifiers);
    }
}
