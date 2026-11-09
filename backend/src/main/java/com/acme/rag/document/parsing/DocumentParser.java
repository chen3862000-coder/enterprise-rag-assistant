package com.acme.rag.document.parsing;

public interface DocumentParser {
    boolean supports(String filename);

    /** Parses bounded source bytes into indexable sections, or throws for malformed content. */
    ParsedDocument parse(byte[] content);
}
