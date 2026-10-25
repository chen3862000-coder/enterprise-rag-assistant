# Retrieval Design

An authenticated query is normalized, embedded with the separately configured Ollama embedding model, then searched through three parameterized PostgreSQL paths:

1. Exact cosine-distance search in pgvector.
2. PostgreSQL full-text search over the generated `tsvector`.
3. Exact substring search for extracted technical identifiers such as error codes and API identifiers.

Every path applies the same document role, enabled-status, and active-version predicates in SQL. Only authorized candidates are returned to the retrieval service. Reciprocal Rank Fusion merges lists without comparing incompatible raw scores and deduplicates on stable chunk IDs.

The deterministic evidence gate is shared by chat and offline evaluation. A candidate passes when a query contains exactly one recognized technical identifier that exactly matches authorized text, or when it has both a PostgreSQL full-text hit above the configured rank threshold and vector similarity above the configured threshold. Exact identifier bypass requires a structured identifier containing letters, or a named numeric status such as HTTP 429. It is disabled for queries containing sensitive requested terms such as credentials, secrets, salaries, and acquisition prices, and for multiple-identifier comparison queries, because substring matching cannot prove the requested fact is present. RRF score is not thresholded because it is a rank-fusion quantity, not a calibrated relevance probability. The gate returns a reason, filters the context to passing chunks, and an insufficient decision sends no source context to generation and returns no citations. Current thresholds are configurable heuristics calibrated against this 56-scenario synthetic English corpus; they are not universal semantic guarantees and may false-abstain or pass lexical/vector coincidence on other corpora.

The context assembler preserves fused order, applies a character budget, escapes source-supplied delimiter tags, and assigns citation IDs only to included chunks. Returned IDs must match supplied IDs. Citation resolution then re-checks authorized active source records. A valid citation means the source is real/current/authorized, not that every claim is factually correct. The API status is `CITATIONS_PRESENT`, deliberately not `SUPPORTED` or `GROUNDED`.

Exact vector search is intentional for the small MVP corpus. Approximate indexes, rerankers, query rewriting, distributed search, and model-based retrieval judgments are not currently part of this implementation.
