package com.acme.rag.document.parsing;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/** A validated regular-file member from a CHM archive listing. */
record ChmMember(String path, long size) {
    static String safePath(String supplied) {
        String normalized = supplied.replace('\\', '/');
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        Path path = Path.of(normalized);
        if (normalized.isBlank() || path.isAbsolute() || normalized.matches("^[A-Za-z]:.*")
                || Arrays.stream(normalized.split("/", -1))
                        .anyMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))) {
            throw ChmDocumentParser.invalid("CHM archive contains an unsafe path");
        }
        return normalized;
    }

    static boolean isHtml(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".htm") || lower.endsWith(".html");
    }
}
