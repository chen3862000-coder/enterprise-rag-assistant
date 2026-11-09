# Backend Parity

## Purpose

Java remains the default implementation and source compatibility baseline. Python is a separately selectable implementation using the same React frontend and Flyway-owned PostgreSQL schema. Recent enhancements implement the same durable ingestion, CHM extraction, multilingual lexical/identifier retrieval, and bounded query planning in both backends. Shared code shape is not proof of complete behavior parity; matched-dataset evaluation and contract tests remain required.

## Contract Matrix

| Area | Java implementation | Python implementation |
|---|---|---|
| Identity | Alice/Bob/Carol roles are server-assigned; Carol's management capability is separate | Fixed identity mapping and admin checks; TestClient verifies login, `/me`, unknown user, CSRF, logout, and cookie rotation |
| API transport | Same-origin paths, request/response field names, statuses, structured application errors | FastAPI routes mirror auth, chat, source, documents, lifecycle, traces, and evaluations; representative TestClient coverage uses PostgreSQL |
| Session | Server-side session and CSRF; roles never come from request data | `python_sessions`, Python-only cookie name, session rotation and invalidation; re-login required when switching implementations |
| Database | Flyway V1-V21; uses shared job, index, planner trace fields; does not use Python session table | Flyway V1-V21; SQLAlchemy Core; adds Python-only `python_sessions` |
| ACL retrieval | SQL role/status/current-version/active-index constraints in vector, FTS, multilingual-term, and identifier channels before fusion | Separate SQL channels apply the same predicates; integration tests assert forbidden chunks stay out of channel results |
| Ingestion | 202 Accepted; streamed durable source; bounded worker pool; database lease/heartbeat/checkpoint; chunk batches; atomic activation; ACL-scoped job polling; Markdown/TXT/PDF/CHM | 202 Accepted; streamed durable source; PostgreSQL worker/lease/checkpoint; chunk batches; atomic activation; ACL-scoped job polling; Markdown/TXT/PDF/CHM |
| CHM | 7-Zip subprocess, bounded archive/listing/page/time/output and safe paths | `7z` subprocess with independent bounds and HTML/table extraction |
| Query planning | Strict typed plan, direct bounded DeepSeek client, original baseline, deterministic fallback including timeout, trace diagnostics; evaluation forces deterministic mode | Strict Pydantic plan, optional DeepSeek/Ollama, original baseline, deterministic fallback including timeout, trace diagnostics |
| Retrieval language | Vector + English FTS + indexed Latin/CJK terms + exact indexed technical identifiers | Vector + English FTS + indexed Latin/CJK terms + exact indexed technical identifiers |
| Evidence and generation | Deterministic gate; answer generation independent from planner; no citations on insufficient evidence | Same intended contract; provider settings independent; retrieval-only status when generation disabled |
| Evaluation | Separate persisted 63-case development and 40-case held-out runs; optional prefix-shadow is disabled by default; evaluation forces deterministic planner | One compatibility run with distinct development/held-out metric groups; prefix-shadow unavailable; planner forced deterministic |
| Traces | Hashed query, bounded planner provider/status/intent/count diagnostics; owner/admin access | Hashed query, bounded planner provider/status/intent/count diagnostics; owner/admin access |
| Document management ACL display | Admin document list includes allowed roles; non-admin responses return an empty role list | Admin document list includes allowed roles; non-admin responses return an empty role list |

## Flyway and Shared Database

Flyway remains the sole migration owner. V12 creates `python_sessions`; V13-V15 track seed ownership/repair; V16-V17 add durable ingestion job state; V18-V19 index/backfill technical identifiers and multilingual lexical terms; V20 adds planner trace diagnostics; V21 maintains those indexes on future chunk writes. Python does not run Alembic. Java does not use Python's session table but uses shared job/index/trace columns.

Java verification has exercised migrations through V21 on empty Testcontainers PostgreSQL/pgvector databases. Python's integration fixture applies the same migration SQL. Both Compose selections run Flyway before backend startup. Back up valuable persistent databases and follow normal migration review; migration compatibility does not imply semantic evaluation parity.

## Evaluation Methodology

Development data: `evals/dataset.jsonl`, Java version `2026-10-p1-v5`, 63 labeled synthetic cases. Held-out data: `python-backend/evals/heldout-paraphrases.jsonl`, version `heldout-paraphrases-v3`, with 40 synthetic scenarios. IDs are disjoint. Java persists two distinct runs and returns two reports; Python retains a compatibility run with independent development/held-out metric groups in configuration. Neither backend should present pooled metrics as either dataset's result. The held-out set has been inspected during development and is exploratory, not blind release evidence.

The initial Python evaluator incorrectly treated post-gate context as its retrieved candidate set, which undercounted false abstentions. It now records pre-gate authorized candidates separately from admitted context, matching Java's metric definition. Corrected Python run `5e292531-531e-45b8-afdb-c2b5f7fbba8d` reports held-out R@5 1.000/MRR 0.923/gate accuracy 0.875, false-abstention 19.23%, false-admission 0%, zero ACL leakage. Final Java runs `8027a2fb-3886-4dde-88b8-80e8259f2afe` (development) and `dea756bd-cc24-4ca2-a60f-b6d55fe8b8c1` (held-out) report held-out R@5 1.000/MRR 0.894/gate accuracy 0.925, false-abstention 11.54%, false-admission 0%, and zero ACL leakage. Remaining Java abstentions are `heldout-022` (request-ID) and `heldout-032`/`heldout-037` (CSD); the candidate has sufficient individual signals, but not all required joint signals on the same chunk. Python has five such false abstentions. The exact-code/mixed-language policy fixes were covered by tests, but these reports precede the final small policy tightening. Reports remain exploratory and do not establish blind or per-scenario parity.

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
- Complete per-scenario Java/Python behavior equality is not established. Separate development/held-out reports are now available, but a matched corpus run with equivalent embedding and deterministic planner settings is required before claiming measured parity.
- Earlier Python run `3d8da3bc-95db-471d-992a-b04e4dcf4e7e` and Java v5 measurements are pre-parity historical baselines. Python fallback planning has since changed to preserve the full query and report unknown intent; do not treat those metrics as current.
- The held-out paraphrases have been inspected and used during development. They are exploratory, not a clean blind acceptance measurement; freeze a new held-out set before policy tuning or release acceptance.
- Compose cold-start requires external Docker image and Ollama model availability. A failure to download either dependency must be reported as infrastructure-dependent, not a passing startup check.
