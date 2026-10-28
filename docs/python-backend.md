# Python Backend

## Status

This document describes the Python implementation added alongside Java. The current branch verification run passed Python unit and PostgreSQL/pgvector integration tests, Java quality gates, frontend unit/build checks, Compose startup, and browser flows. The exact commands and current evaluation outcome are recorded in the implementation report. Live DeepSeek generation is optional and is not required for deterministic CI.

The Python service is a portfolio implementation, not production SSO or a production-ready SaaS service. It uses synthetic company data. Application-level SQL authorization is tested; PostgreSQL row-level security is not configured.

## Architecture and Dependencies

The service is a FastAPI modular application with Pydantic v2 request models, SQLAlchemy 2 Core, Psycopg 3, PostgreSQL/pgvector, HTTPX adapters, and Tenacity for bounded transient retry. `uv.lock` pins the dependency graph. pytest and testcontainers-python run unit and real PostgreSQL integration tests; Ruff and mypy enforce style and typing.

LangChain is not used. Provider calls are narrow HTTP adapters, so adding an orchestration framework would obscure rather than simplify authorization, lifecycle transactions, and deterministic evaluation. LlamaIndex is not used: parsing, chunking, and retrieval are implemented explicitly to preserve the Java document model and stable chunk semantics.

The React application calls same-origin `/api/...` endpoints through Nginx. The Java Compose service remains the default. `compose.python.yaml` overrides the service named `backend`; Nginx continues routing to `backend:8080`, and only one backend service is selected per Compose invocation.

## Shared Database and Migrations

Flyway is the only migration owner. Python maps the existing tables with SQLAlchemy Core and does not create an Alembic history or competing schema. Migrations V12-V15 add the Python server-side session table and explicit seed repair state, then mark the known synthetic corpus IDs as seed-managed. Java and Python both apply the additive migrations; Java does not otherwise depend on the Python session table.

Python startup requires Flyway to have run. The alternate Compose file starts `schema-init` using the Java Flyway scripts before the Python backend. Testcontainers applies those same Flyway SQL files to an empty pgvector database. Compatibility with the Java-created schema is exercised by using the exact same migrations and final table definitions; a separate persistent-database operator procedure is described in `backend-parity.md`.

## Authentication and Authorization

Demo login accepts only `alice`, `bob`, or `carol`; the server assigns roles. Alice has `PUBLIC` and `ENGINEERING`, Bob has `PUBLIC` and `HR`, and Carol has `PUBLIC` and `SECURITY_ADMIN` plus a separate document-admin capability. Unknown identities are rejected. This is a demo identity selector, not production SSO.

Python uses opaque server-side sessions stored in `python_sessions`, HTTP-only same-site cookies, session rotation on login, CSRF token validation, expiry, and logout invalidation. The Python cookie name differs from Java's servlet session cookie, so selecting a different backend requires signing in again. The frontend source does not change.

Every vector, PostgreSQL full-text, and exact-identifier retrieval query filters by active document, current active version, active index state, and trusted roles through `document_permissions` before candidates are returned to application fusion. Direct source reads repeat the active-version/role check. Document lists and version lists are role-filtered unless the trusted identity has the explicit admin capability. Trace detail is owner/admin scoped.

## Ingestion and Lifecycle

The service accepts Markdown, TXT, and extractable PDF content, validates file size, filename extension/signature, UTF-8, and text control characters, hashes raw content with SHA-256, and preserves heading/page metadata where available. Character chunking follows the Java defaults: 1800-character target with 240-character overlap. PDF extraction uses `pypdf`, while Java uses PDFBox; exact extracted PDF text parity is not guaranteed and remains a known difference.

The SQL lifecycle uses a PostgreSQL transaction advisory lock per logical document, stages an `INDEXING` version/job, parses/chunks/embeds, then transactionally persists chunks, supersedes the former active version, activates the staged version, updates the current pointer/permissions, and completes the job. A failed new version leaves the prior active pointer intact. Identical current content updates metadata/permissions without re-embedding. Disable/delete are immediate SQL retrieval exclusions; restore is explicit.

Seed-managed documents carry explicit repair state. Only a failed first seed attempt marked repairable may be retried by seeding. Administrator disable/delete/restore clears that state, so seeding does not reactivate an administrator-inactive document. Provider retries are bounded and warning-logged without source content or secrets.

## Retrieval, Generation, and Citations

Retrieval runs exact pgvector, PostgreSQL full-text OR-term search, and exact technical-identifier lookup. SQL authorization runs in each candidate query before deterministic RRF and deduplication. The evidence gate uses exact identifiers or same-candidate vector, keyword, and adaptive lexical-coverage signals, with deterministic sensitive-request and domain/role-scope rejections. Its implementation is independently tested but is not yet asserted equivalent across every one of Java's specialized gate labels; see parity limitations.

Retrieved content is untrusted. System instructions and evidence are separate provider messages, source delimiters are escaped, and adversarial content tests cover attempts to override instructions. This is risk reduction, not a claim of prompt-injection immunity.

DeepSeek generation is optional, uses environment-injected API keys, explicit timeouts, bounded retries only for transient failures, and logs neither keys nor raw query/source content. `CHAT_MODEL_PROVIDER=none` returns a `RETRIEVAL_ONLY` status and reauthorized citations when the gate passes. Insufficient evidence returns no citations. Citations are checked against supplied evidence and reauthorized against current SQL ACL/version state. This does not establish factual entailment.

Traces store query hashes, identity, bounded counts/timings, provider/model, evidence reason, citation count, response mode, error category, and status; raw query/source content is not persisted. Trace retention cleanup and bounded metric labels are implemented for the demo contract.

## Evaluation

The existing 63-scenario development dataset is reused without relabeling. A separate frozen 14-scenario synthetic paraphrase dataset is stored under `python-backend/evals/heldout-paraphrases.jsonl`; it is not used to tune evidence rules. Evaluation reports development and held-out metric groups independently, while its persisted Java-compatible summary uses the combined run and stores per-scenario diagnostics in the shared Flyway tables.

The evaluator measures retrieval Recall@5/MRR, ACL leakage, freshness, citation-ID validation, gate accuracy, false-abstention/admission, ablations, and release quality. Prefix-shadow metrics are explicitly marked unavailable in Python rather than fabricated. Evaluation is deterministic and does not call DeepSeek. After aligning keyword coverage with PostgreSQL English FTS term/stemming semantics and matching Java's `minimum-keyword-score` of `0.3`, a fresh **development-only** 63-case run (`3d8da3bc-95db-471d-992a-b04e4dcf4e7e`) reported Recall@5 1.000, MRR 0.912, ACL leakage 0, freshness 1.000, citation-ID validity 1.000, evidence-gate accuracy 0.968, false-abstention 5.71%, false-admission 0.0%, and release quality PASS. The two false abstentions are `rag-020` and `rag-028`, the same cases documented for Java. No held-out cases were accessed for this run. An earlier combined run using the prior policy had held-out false-abstention of 37.5%; because held-out cases had already been inspected during earlier implementation, that is exploratory, not a blind acceptance result. A new held-out set is required before further tuning.

## Run and Test

Java remains the default:

```bash
docker compose up --build
```

Python backend:

```bash
docker compose -f compose.yaml -f compose.python.yaml up --build --wait
```

Stop the selected Compose project with the same `-f` arguments and `down`. Compose profiles are intentionally not used because they would not disable the unprofiled Java service.

Local Python quality checks:

```bash
cd python-backend
uv sync --all-groups
uv run ruff format --check app tests
uv run ruff check app tests
uv run mypy app
uv run pytest
uv run pip-audit
```

`pytest` uses real PostgreSQL/pgvector Testcontainers and enforces at least 70% total statement/branch coverage. It does not substitute SQLite.

Python-backed browser flows, with Playwright installed in the frontend dependency set:

```bash
cd frontend
E2E_COMPOSE_FILES='../compose.yaml,../compose.python.yaml' \
E2E_COMPOSE_PROJECT=enterprise-rag-python-e2e FRONTEND_PORT=3001 \
E2E_BASE_URL=http://127.0.0.1:3001 npm run test:e2e
```

## Known Differences and Limitations

- Python sessions use the additive `python_sessions` table and a separate cookie name; sessions do not interoperate with Java sessions.
- PDF parsing can differ from PDFBox. Golden synthetic text fixtures cover deterministic chunking; broad PDF byte-for-byte parity is not claimed.
- The Python evaluator marks prefix-shadow metrics unavailable and does not yet reproduce Java's in-memory Nomic task-prefix shadow experiment.
- The Python evidence policy ports core thresholds, exact identifiers, and major scope/sensitive/hard-negative rules, but complete parity with all Java intent cases requires direct cross-backend dataset metrics and contract review.
- Held-out metrics were inspected before all final rule and plumbing refinements. Treat the observed result as exploratory, not as an uncontaminated blind acceptance measurement; freeze a fresh held-out set before any further tuning.
- Live DeepSeek behavior is optional and unverified unless separately smoke-tested with an injected key.
- Demo identities, local container defaults, application SQL ACLs without RLS, synthetic calibrated thresholds, and lack of SSO/DLP/distributed indexing remain explicit MVP limits.

Run the existing Playwright workflows against Python from `frontend/`:

```bash
E2E_COMPOSE_FILES='../compose.yaml,../compose.python.yaml' \
E2E_COMPOSE_PROJECT=enterprise-rag-python-e2e FRONTEND_PORT=3001 \
E2E_BASE_URL=http://127.0.0.1:3001 \
npm run test:e2e
```

The E2E runner always invokes `down` for the same project in a `finally` path, including when browser tests fail. A distinct project name avoids stopping a developer's separately running default Java stack.
