# Backend Parity

## Purpose

Java remains the default implementation and source compatibility baseline. Python is a separate implementation intended to use the same React frontend and Flyway-owned PostgreSQL schema. This document distinguishes shared contracts from language-specific implementation and records verification requirements; it does not assert complete parity solely because both services expose similarly named routes.

## Contract Matrix

| Area | Shared contract | Python implementation/verification |
|---|---|---|
| Identity | Alice/Bob/Carol roles are server-assigned; Carol's management capability is separate | Fixed identity mapping and admin checks; TestClient verifies login, `/me`, unknown user, CSRF, logout, and cookie rotation |
| API transport | Same-origin paths, request/response field names, statuses, structured application errors | FastAPI routes mirror auth, chat, source, documents, lifecycle, traces, and evaluations; representative TestClient coverage uses PostgreSQL |
| Session | Server-side session and CSRF; roles never come from request data | `python_sessions`, Python-only cookie name, session rotation and invalidation; re-login required when switching implementations |
| Database | PostgreSQL/pgvector and Flyway-owned schema | Migrations V1-V15 run against empty Testcontainers; Python mappings use shared document/evaluation/trace tables |
| ACL retrieval | SQL role/status/current-version/active-index constraints before fusion | Separate SQL vector/FTS/exact queries include these predicates; PostgreSQL integration test verifies each method returns no HR-only row to Engineering |
| Ingestion | Stable IDs, staged indexing, atomic activation, old version survives failure, duplicate content avoids embed | PostgreSQL integration tests cover activation, failure preservation, duplicate metadata/ACL update, source reauthorization, disable/restore, seed repair |
| Evidence and generation | Deterministic rejection, no citations on insufficient evidence, retrieval-only labeled distinctly | Unit/API tests cover core policy and no-provider path; full scenario parity against Java still requires controlled corpus comparison |
| Evaluation | Existing 63 dev labels/diagnostics and release thresholds; held-out data reported separately | 63 development plus 14 held-out cases; prefix shadow marked unavailable, not approximated |
| Traces | Hashed query, bounded operational fields, owner/admin access | Uses shared `rag_traces`; trace retrieval shape and source data exclusion implemented |

## Flyway and Shared Database

Flyway remains the sole migration owner. V12 creates `python_sessions`; V13 adds `seed_managed`; V14 adds `seed_repairable`. Python does not run Alembic. Java code does not use Python's session table, but applies the migrations normally.

Java was verified to migrate an empty Testcontainers pgvector database through V14. Python's integration fixture applies the same migration SQL to a real pgvector Testcontainer and exercises document/version/chunk/trace/evaluation tables. The Python Compose file runs a Flyway container before Python starts. A compatible pre-existing Java database is therefore upgraded by the same Flyway history; deployment against a valuable existing database should still be backed up and run through the repository's normal migration review process.

## Evaluation Methodology

Development data: `evals/dataset.jsonl`, Java version `2026-10-p1-v5`, 63 labeled synthetic cases. Held-out data: `python-backend/evals/heldout-paraphrases.jsonl`, 14 separately frozen paraphrases. IDs are disjoint and the held-out file was created separately from the rule port. Results must always report development and held-out metrics independently. The stored compatibility summary currently uses a combined dataset version and combined counts; the independent metric groups are retained in run configuration.

Release checks require zero ACL leakage, 100% freshness, 100% citation-ID validity, false-abstention at most 20%, and false-admission at most 5%, plus the Java minimum positive/negative case counts for release quality. Prefix-shadow shadow evaluation is unavailable in Python and appears as `available: false`; it must not be interpreted as a zero-score experiment.

## Verification Status and Reproduction

At implementation completion, rerun and report these commands from the branch:

```bash
# Java, Java 21
cd backend && ./mvnw verify

# Python
cd python-backend
uv sync --all-groups
uv run ruff format --check app tests
uv run ruff check app tests
uv run mypy app
uv run pytest
uv run pip-audit

# Frontend
cd frontend
npm ci
npm run build
npm test -- --run
npm audit --audit-level=moderate

# Compose manifest validation
docker compose config --quiet

# Python browser workflow (stops its managed Compose stack in finally)
E2E_COMPOSE_FILES='../compose.yaml,../compose.python.yaml' \
E2E_COMPOSE_PROJECT=enterprise-rag-python-e2e FRONTEND_PORT=3001 \
E2E_BASE_URL=http://127.0.0.1:3001 npm run test:e2e
```

Live provider generation requires an externally injected `DEEPSEEK_API_KEY` and remains separate from deterministic verification. Never store the key, raw customer queries, private traces, or private evaluation outputs in the repository.

## Residual Differences and Risks

- Session cookie name/storage differs by backend; existing frontend API calls remain unchanged, but backend switching requires login again.
- Parser-library PDF extraction is not guaranteed byte-identical between pypdf and PDFBox.
- Python prefix-shadow evaluation is unavailable.
- Exact parity for all Java specialized evidence-gate rejection labels and the full 63-case retrieval outcome is not established by unit tests alone. A full Java/Python corpus contract/evaluation comparison remains a required acceptance step before claiming complete behavior equivalence.
- Held-out paraphrase metrics were inspected during the implementation session, then behavior was refined. They are exploratory and not a clean blind acceptance measurement; a new frozen held-out set is required before further policy tuning.
- After aligning coverage to PostgreSQL English FTS terms and matching Java's effective `minimum-keyword-score` of `0.3`, clean development-only run `3d8da3bc-95db-471d-992a-b04e4dcf4e7e` produced 96.83% gate accuracy, 5.71% false-abstention, 0% false-admission, zero ACL leakage, 100% freshness and citation-ID validity, Recall@5 1.0, and MRR 0.912. The same two development cases (`rag-020`, `rag-028`) remain false-abstained as in the documented Java run. The held-out set was not loaded or scored in this run. Previous combined-run held-out results were inspected during implementation and are exploratory only, not a blind acceptance measurement.
- Compose cold-start requires external Docker image and Ollama model availability. A failure to download either dependency must be reported as infrastructure-dependent, not a passing startup check.
