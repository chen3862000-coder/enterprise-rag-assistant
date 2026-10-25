package com.acme.rag.citation;

import com.acme.rag.retrieval.RankedChunk;

public record CitationEvidence(String citationId, RankedChunk chunk) {}
