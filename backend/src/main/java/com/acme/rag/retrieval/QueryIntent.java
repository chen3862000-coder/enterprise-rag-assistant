package com.acme.rag.retrieval;

/** Coarse response intent from user text; it never grants retrieval access. */
public enum QueryIntent {
    LOOKUP,
    EXPLAIN,
    COMPARE,
    PROCEDURE,
    TROUBLESHOOT,
    SUMMARIZE,
    UNKNOWN
}
