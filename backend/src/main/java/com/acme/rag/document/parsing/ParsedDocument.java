package com.acme.rag.document.parsing;

import java.util.List;

public record ParsedDocument(List<Section> sections) {
    public ParsedDocument {
        sections = List.copyOf(sections);
    }

    public record Section(String headingPath, String text, Integer pageNumber) {}
}
