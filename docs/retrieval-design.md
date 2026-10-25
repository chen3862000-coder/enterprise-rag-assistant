# Retrieval Design

An authenticated query is normalized, embedded with the separately configured Ollama embedding model, then searched through three parameterized PostgreSQL paths:

1. Exact cosine-distance search in pgvector.
2. PostgreSQL full-text search over the generated `tsvector`.
3. Exact substring search for extracted technical identifiers such as error codes and API identifiers.

Every path applies the same document role, enabled-status, and active-version predicates in SQL. Only authorized candidates are returned to the retrieval service. Reciprocal Rank Fusion merges lists without comparing incompatible raw scores and deduplicates on stable chunk IDs.

The context assembler preserves fused order, applies a character budget, escapes source-supplied delimiter tags, and assigns citation IDs only to included chunks. Returned IDs must match supplied IDs. Citation resolution then re-checks authorized active source records. A valid citation means the source is real/current/authorized, not that every claim is factually correct.

Exact vector search is intentional for the small MVP corpus. Approximate indexes, rerankers, query rewriting, distributed search, and model-based retrieval judgments are not currently part of this implementation.
