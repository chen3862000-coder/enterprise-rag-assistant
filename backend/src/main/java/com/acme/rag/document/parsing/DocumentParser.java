package com.acme.rag.document.parsing;

public interface DocumentParser {
    boolean supports(String filename);

    ParsedDocument parse(byte[] content);
}
