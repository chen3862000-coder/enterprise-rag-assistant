package com.acme.rag.document.chunking;

public record DocumentChunk(int index, String text, String headingPath, Integer pageNumber) {}
