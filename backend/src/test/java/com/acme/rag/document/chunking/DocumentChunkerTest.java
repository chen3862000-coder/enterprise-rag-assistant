package com.acme.rag.document.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.rag.document.parsing.ParsedDocument;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentChunkerTest {
    @Test
    void preservesHeadingAndPageMetadataAcrossChunks() {
        DocumentChunker chunker = new DocumentChunker(12, 3);
        ParsedDocument parsed = new ParsedDocument(List.of(new ParsedDocument.Section("Policy > Limits", "alpha beta gamma delta", 4)));

        List<DocumentChunk> chunks = chunker.chunk(parsed);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.headingPath()).isEqualTo("Policy > Limits");
            assertThat(chunk.pageNumber()).isEqualTo(4);
        });
        assertThat(chunks).extracting(DocumentChunk::index).containsExactly(0, 1, 2);
    }
}
