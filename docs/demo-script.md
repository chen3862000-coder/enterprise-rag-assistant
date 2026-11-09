# 3–5 Minute Demo Script

Use the synthetic Acme Enterprise corpus and local Compose stack. For deterministic gate demonstrations, `CHAT_MODEL_PROVIDER=none` is acceptable. To demonstrate generated prose, enable DeepSeek with an owner-provided key. Distinguish model output from source authorization; citations do not prove entailment.

## Setup

1. Start services with `docker compose up --build -d` and wait for `docker compose ps` healthchecks. Compose defaults to deterministic query planning; enable DeepSeek planning separately with `QUERY_PLANNER_PROVIDER=deepseek`, a `DEEPSEEK_API_KEY`, and `QUERY_PLANNER_ENABLED=true`.
2. Open `http://localhost:3000`.
3. Use Alice, Bob, and Carol identities only; the server determines roles.

## Walkthrough

1. **Authorized and weak evidence, 60 seconds:** As Alice, ask about the Enterprise rate limit and HTTP 429. Show the gate decision/reason in the trace, then open citations. State that citation IDs are source references, not factual verification.
2. **Permission isolation, 60 seconds:** Ask about HR leave as Bob, then ask the same question as Alice. Bob may retrieve HR evidence; Alice should get `INSUFFICIENT_EVIDENCE` and no citations. As Alice, try the copied Bob source chunk ID; it should not resolve.
3. **Same-content ACL revocation, 60 seconds:** As Carol, re-upload identical HR content under the same logical document ID but narrow/change allowed roles. Confirm the duplicate result creates no embedding/version work but updates metadata/ACL; then verify the revoked role can no longer retrieve the chunk.
4. **Durable ingestion and version freshness, 60 seconds:** Upload changed content. Show the accepted job ID and progress polling; confirm the previous active version remains queryable until all staged chunks activate atomically.
5. **Multilingual planning, 45 seconds:** Compare “What is OKR?” and “什么是OKR?”. If the optional planner is enabled, inspect the trace for planner provider/status and show the bounded rewrite diagnostics. Explain that timeout falls back deterministically, the original question remains a baseline, and authorization remains SQL-controlled.
6. **Measured evaluation, 60 seconds:** Run Carol's offline suite. Show the 63-case development and 40-case held-out reports independently; do not pool metrics or tune on the held-out set. Prefix shadow is disabled by default; enable it separately only for that diagnostic. State clearly that these are retrieval/gate results, not generated-answer accuracy or LLM abstention accuracy, and discuss the current held-out false-abstention gap.

## Limits to State

- The demo identity selector is not password authentication or SSO.
- Evidence gate thresholds are deterministic MVP heuristics; they are calibrated against this synthetic corpus and are not universal relevance guarantees.
- `CITATIONS_PRESENT` means only that supplied authorized source IDs are present; it is not a fact-check or entailment judgment.
- No production DLP, PostgreSQL row-level security, or live judge evaluation is claimed.
