# Python Backend

## Status

This document describes the Python implementation added alongside Java. The current branch verification run passed Python unit and PostgreSQL/pgvector integration tests, Java quality gates, frontend unit/build checks, Compose startup, and browser flows. The exact commands and current evaluation outcome are recorded in the implementation report. Live DeepSeek generation is optional and is not required for deterministic CI.

The Python service is a portfolio implementation, not production SSO or a production-ready SaaS service. It uses synthetic company data. Application-level SQL authorization is tested; PostgreSQL row-level security is not configured.

Query understanding is Python-backend-only. It preserves the original message as a retrieval baseline, deterministically normalizes a limited set of prefixes for fallback, and uses one bounded structured planner call for semantic query understanding when configured. The Python Compose overlay defaults to DeepSeek with a 5-second timeout; set `QUERY_PLANNER_PROVIDER=deterministic` to disable remote planning, or `QUERY_PLANNER_PROVIDER=ollama` and `QUERY_PLANNER_MODEL` to use a local chat model. Without a configured DeepSeek key or local model, planning falls back deterministically. Plans contain retrieval formulations and response intent only; server-controlled identity and SQL ACL filters are never planner inputs or outputs. Planner prompts request canonical subject terms and meaning-preserving multilingual formulations instead of a language-specific list of question prefixes.

## Architecture and Dependencies

The service is a FastAPI modular application with Pydantic v2 request models, SQLAlchemy 2 Core, Psycopg 3, PostgreSQL/pgvector, HTTPX adapters, and Tenacity for bounded transient retry. `uv.lock` pins the dependency graph. pytest and testcontainers-python run unit and real PostgreSQL integration tests; Ruff and mypy enforce style and typing.

LangChain is not used. Provider calls are narrow HTTP adapters, so adding an orchestration framework would obscure rather than simplify authorization, lifecycle transactions, and deterministic evaluation. LlamaIndex is not used: parsing, chunking, and retrieval are implemented explicitly to preserve the Java document model and stable chunk semantics.

The React application calls same-origin `/api/...` endpoints through Nginx. The Java Compose service remains the default. `compose.python.yaml` overrides the service named `backend`; Nginx continues routing to `backend:8080`, and only one backend service is selected per Compose invocation.

## Shared Database and Migrations

Flyway is the only migration owner. Python maps the existing tables with SQLAlchemy Core and does not create an Alembic history or competing schema. Migrations V12-V15 add the Python server-side session table and explicit seed repair state, V16 adds an ingestion heartbeat, V17 adds durable source/job progress, V18 adds indexed technical identifiers and multilingual lexical terms, and V19 removes ancestor-folder terms from existing lexical indexes. Java and Python both apply the additive migrations; Java does not otherwise depend on the Python session or worker/index columns.

Python startup requires Flyway to have run. The alternate Compose file starts `schema-init` using the Java Flyway scripts before the Python backend. Testcontainers applies those same Flyway SQL files to an empty pgvector database. Compatibility with the Java-created schema is exercised by using the exact same migrations and final table definitions; a separate persistent-database operator procedure is described in `backend-parity.md`.

## Authentication and Authorization

Demo login accepts only `alice`, `bob`, or `carol`; the server assigns roles. Alice has `PUBLIC` and `ENGINEERING`, Bob has `PUBLIC` and `HR`, and Carol has `PUBLIC` and `SECURITY_ADMIN` plus a separate document-admin capability. Unknown identities are rejected. This is a demo identity selector, not production SSO.

Python uses opaque server-side sessions stored in `python_sessions`, HTTP-only same-site cookies, session rotation on login, CSRF token validation, expiry, and logout invalidation. The Python cookie name differs from Java's servlet session cookie, so selecting a different backend requires signing in again. The frontend source does not change.

Every vector, PostgreSQL full-text, and exact-identifier retrieval query filters by active document, current active version, active index state, and trusted roles through `document_permissions` before candidates are returned to application fusion. Direct source reads repeat the active-version/role check. Document lists and version lists are role-filtered unless the trusted identity has the explicit admin capability. Trace detail is owner/admin scoped.

## Ingestion and Lifecycle

The service accepts Markdown, TXT, extractable PDF, and CHM. Uploads are streamed to a private temporary file, hashed incrementally, and limited to 50 MiB by default (`MAXIMUM_UPLOAD_BYTES`). Text must be valid UTF-8; PDF and CHM signatures must match their extensions. CHM extraction requires the `7z` executable (Debian `7zip` package in the Python image), processes HTML pages only, and defaults to limits of 20,000 archive entries, 256 MiB expanded member data, and 2 MiB per HTML page. Legacy Chinese HTML without charset metadata falls back to GB18030. Temporary content is deleted on request completion. Character chunking follows the Java defaults: 1800-character target with 240-character overlap. PDF extraction uses `pypdf`, while Java uses PDFBox; exact extracted PDF text parity is not guaranteed and remains a known difference.

The SQL lifecycle uses a PostgreSQL transaction advisory lock per logical document, stages an `INDEXING` version/job, then parses and embeds bounded batches. Multipart upload validates and stores the source in the configured durable source directory, reserves the job, and returns `202 Accepted` with a job ID. One PostgreSQL-advisory-locked worker processes jobs sequentially; the frontend polls the ACL-authorized ingestion status endpoint and displays committed chunk counts. Each chunk batch is committed to the inactive version in a short transaction; retrieval and source queries require the current version and `ACTIVE` status, so staged rows are never visible. The committed chunk count is the restart checkpoint: after a worker process restarts, it rereads the durable source and skips already persisted chunk indexes. Final activation validates the staged count and atomically supersedes the former version, activates the new version, updates the current pointer/permissions, and completes the job. Transient embedding errors use bounded exponential backoff; failed work leaves the prior active pointer intact. Sources for completed/terminal jobs are deleted. Stale legacy jobs without durable sources are failed after `STALE_INGESTION_SECONDS` (default one hour). Identical current content updates metadata/permissions without re-embedding. Disable/delete are immediate SQL retrieval exclusions; restore is explicit. The source directory must be persistent and shared by backend restarts; Docker Compose provisions a named volume. Uploads above the configured limit are not supported.

Seed-managed documents carry explicit repair state. Only a failed first seed attempt marked repairable may be retried by seeding. Administrator disable/delete/restore clears that state, so seeding does not reactivate an administrator-inactive document. Provider retries are bounded and warning-logged without source content or secrets.

## Retrieval, Generation, and Citations

Retrieval normalizes Unicode with NFKC, then runs exact pgvector, PostgreSQL English full-text OR-term search, indexed Latin/CJK lexical-term lookup, and exact technical-identifier lookup. CJK text is represented by overlapping bigrams at indexing and query time; Latin tokens, headings, and extracted technical symbols are indexed alongside source chunks. Every retrieval path applies the same SQL active-version and role ACL predicates before RRF and fusion. The shared evidence gate is language-neutral: exact indexed identifiers require vector support, and other candidates must jointly meet vector, keyword, and adaptive full-query lexical-coverage thresholds. Sensitive and domain/role-scope rejection checks run before context admission. CHM extraction preserves page headings, table row boundaries, and source-local identifiers; these metadata do not authorize bypass of the shared evidence gate.

Retrieved content is untrusted. System instructions and evidence are separate provider messages, source delimiters are escaped, and adversarial content tests cover attempts to override instructions. This is risk reduction, not a claim of prompt-injection immunity.

DeepSeek generation is optional, uses environment-injected API keys, explicit timeouts, bounded retries only for transient failures, and logs neither keys nor raw query/source content. `CHAT_MODEL_PROVIDER=none` returns a `RETRIEVAL_ONLY` status and reauthorized citations when the gate passes. Insufficient evidence returns no citations. Citations are checked against supplied evidence and reauthorized against current SQL ACL/version state. This does not establish factual entailment.

Traces store query hashes, identity, bounded counts/timings, provider/model, evidence reason, citation count, response mode, error category, and status; raw query/source content is not persisted. Trace retention cleanup and bounded metric labels are implemented for the demo contract.

## Evaluation

The existing 63-scenario development dataset is reused without relabeling. A versioned 40-scenario synthetic held-out set includes English, Chinese, mixed-script, identifier, CSD paraphrase, ACL, and sensitive hard-negative cases under `python-backend/evals/heldout-paraphrases.jsonl`. It is stored separately and excluded from threshold fitting. Evaluation reports development and held-out metric groups independently, while its persisted Java-compatible summary uses the combined run and stores per-scenario diagnostics in the shared Flyway tables. The current local held-out result is exploratory because these paraphrases have been iterated on; freeze a fresh held-out set for release acceptance.

The evaluator measures retrieval Recall@5/MRR, ACL leakage, freshness, citation-ID validation, gate accuracy, false-abstention/admission, ablations, and release quality. Expected intent labels are compared with the deterministic planner prediction; they do not override it. Gate accuracy compares the actual pass/abstain outcome with whether the expected evidence was retrieved, so passing on only irrelevant evidence counts as a false admission. Prefix-shadow metrics are explicitly marked unavailable in Python rather than fabricated. Evaluation is deterministic and does not call DeepSeek. The verified 103-scenario local run reports held-out Recall@5 0.962, MRR 0.923, gate accuracy 0.975, zero ACL leakage, zero false abstentions, and one false admission among 15 negative cases (6.7%); aggregate release quality passes its configured threshold. The held-out paraphrase group (cases 31-38) has 1.000 combined Recall@5 and 1.000 intent accuracy, while rewritten-query-only Recall@5 is 0.875. These results are exploratory because the set has been inspected during development. The remaining false admission is `heldout-004`, whose broad release query admits `build-vs-buy` instead of its labeled deployment-procedure source; it is retained as a gate error and no thresholds were weakened. The checked-in synthetic corpus does not include the local Choice Manual, so these metrics do not validate CHM function retrieval; the local live CSD smoke test is separate evidence. Freeze a new corpus-specific held-out set before further release tuning.

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
