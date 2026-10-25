package com.acme.rag.document.chunking;

import com.acme.rag.document.parsing.ParsedDocument;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class DocumentChunker {
    private final int chunkSize;
    private final int overlap;

    public DocumentChunker(
            @Value("${app.ingestion.chunk-size:1800}") int chunkSize,
            @Value("${app.ingestion.chunk-overlap:240}") int overlap) {
        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("Chunk settings are invalid");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public List<DocumentChunk> chunk(ParsedDocument document) {
        List<DocumentChunk> chunks = new ArrayList<>();
        for (ParsedDocument.Section section : document.sections()) {
            appendSection(chunks, section);
        }
        return List.copyOf(chunks);
    }

    private void appendSection(List<DocumentChunk> chunks, ParsedDocument.Section section) {
        String text = section.text().strip();
        for (int start = 0; start < text.length();) {
            int end = findEnd(text, start);
            String slice = text.substring(start, end).strip();
            if (!slice.isEmpty()) {
                chunks.add(new DocumentChunk(chunks.size(), slice, section.headingPath(), section.pageNumber()));
            }
            if (end == text.length()) {
                return;
            }
            start = Math.max(start + 1, end - overlap);
        }
    }

    private int findEnd(String text, int start) {
        int end = Math.min(start + chunkSize, text.length());
        if (end < text.length()) {
            int boundary = text.lastIndexOf(' ', end);
            if (boundary > start + chunkSize / 2) {
                return boundary;
            }
        }
        return end;
    }
}
