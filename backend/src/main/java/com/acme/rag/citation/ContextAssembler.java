package com.acme.rag.citation;

import com.acme.rag.retrieval.RankedChunk;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class ContextAssembler {
    private final int maxCharacters;

    public ContextAssembler(@Value("${app.retrieval.max-context-characters:12000}") int maxCharacters) {
        if (maxCharacters < 1) {
            throw new IllegalArgumentException("Context limit must be positive");
        }
        this.maxCharacters = maxCharacters;
    }

    public AssembledContext assemble(List<RankedChunk> chunks) {
        List<CitationEvidence> evidence = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        for (RankedChunk chunk : chunks) {
            String citationId = "S" + (evidence.size() + 1);
            String entry = format(citationId, chunk);
            if (context.length() + entry.length() > maxCharacters) {
                break;
            }
            evidence.add(new CitationEvidence(citationId, chunk));
            context.append(entry);
        }
        return new AssembledContext(context.toString(), evidence);
    }

    private static String format(String citationId, RankedChunk chunk) {
        String sourceText = chunk.text().replace("<", "\\u003c").replace(">", "\\u003e");
        return "[" + citationId + "] " + chunk.title() + " (version " + chunk.version() + ")"
                + (chunk.section() == null || chunk.section().isBlank() ? "" : " - " + chunk.section())
                + "\n<untrusted_source>\n" + sourceText + "\n</untrusted_source>\n\n";
    }
}
