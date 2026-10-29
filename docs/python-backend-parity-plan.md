# Python Backend Parity Plan

## Purpose

Add a separately runnable Python implementation of the current Enterprise RAG Assistant backend while retaining the Java 21/Spring Boot implementation unchanged except for verified, shared fixes. The React frontend should use either backend through the existing same-origin API contract. The goal is to demonstrate that the current secure RAG architecture can be implemented in a second language, not to replace it with a basic upload-and-chat service.

This document records the agreed design and delivery plan. It does not claim that Python behavior, API compatibility, evaluation thresholds, or runtime workflows have been implemented or verified.

## Repository Baseline

The current system is a modular Java backend with a React/Vite frontend, PostgreSQL/pgvector, Flyway migrations, Ollama embeddings, and optional DeepSeek generation. The frontend calls same-origin `/api/...` endpoints through Nginx, which currently proxies to a Compose service named `backend` on port 8080.

The Java API includes:

- `GET /api/auth/csrf`, `POST /api/auth/login`, `GET /api/auth/me`, and `POST /api/auth/logout`.
- `POST /api/chat` and `GET /api/sources/{chunkId}`.
- Document list, upload, reindex, version list, disable, soft-delete, and explicit restore endpoints.
- Evaluation run/list/detail and trace detail endpoints.
- Spring Actuator health endpoints used operationally by Compose.

The browser uses cookie-backed sessions and sends the CSRF token for mutating requests. Application errors generally use `{ "code": "...", "message": "..." }`; endpoint-specific status behavior, including 204 mutation responses, 401 identity failures, and 404 for inaccessible sources, is part of the compatibility target.

Java demo identities are server-resolved: Alice has `PUBLIC` and `ENGINEERING`, Bob has `PUBLIC` and `HR`, and Carol has `PUBLIC` and `SECURITY_ADMIN`. Carol has separate document-management/evaluation capability; that capability does not expand her retrieval roles.

Flyway owns the current shared schema through V11. It includes documents, permissions, versions, chunks, ingestion jobs, traces, evaluation runs, scenario results, and gate diagnostics. Retrieval uses SQL-side permissions, document status, current-version, and active-version predicates before candidates reach fusion. Current retrieval does not apply an additional classification predicate.

The current chunker uses deterministic character-based chunks (1800-character target and 240-character overlap), with parser-provided heading and page metadata. Evaluation uses the versioned 63-scenario dataset `2026-10-p1-v5`, including diagnostics, ablations, and prefix-shadow metrics. A prior documented run reports Recall@5 1.000, MRR 0.912, ACL leakage 0, freshness 1.000, citation-ID validation 1.000, evidence-gate accuracy 0.968, false-abstention 5.7%, false-admission 0.0%, and release quality PASS. These figures are historical baseline documentation, not results from the Python implementation.

Baseline verification during plan preparation:

- Java `./mvnw verify` under Java 21 passed: 66 unit tests, 39 PostgreSQL/pgvector integration tests, JaCoCo thresholds, SpotBugs, PMD, and architecture tests.
- Frontend install/build/tests passed: all 5 frontend tests passed. `npm audit` was not run during that audit.
- Compose configuration validated and the existing services were healthy. Cold startup, Playwright, and a fresh evaluation run were not run during that audit.
- The worktree was clean during the audit. Do not treat these baseline results as verification of future changes.

## Goals and Non-Goals

### Goals

- Implement the existing backend capabilities in an independently runnable Python service.
- Preserve frontend API behavior, including identity, CSRF/session flow, payloads, errors, lifecycle actions, citations, traces, evaluation responses, and health checks.
- Keep ACL filtering and lifecycle guarantees explicit in PostgreSQL SQL and transactions.
- Keep evidence sufficiency deterministic and shared in behavior between online chat and offline evaluation.
- Reuse synthetic data and the existing evaluation labels; add a frozen held-out paraphrase set for separate reporting.
- Verify database, browser, API, and evaluation behavior rather than claiming parity from code resemblance.

### Non-Goals

- Replacing or weakening the Java backend.
- Introducing an orchestration framework as the application architecture.
- Adding production SSO, PostgreSQL RLS, OCR, distributed indexing, approximate vector indexes, or claims of prompt-injection immunity.
- Claiming Python is faster, safer, or more production-ready without measured evidence.
- Claiming citation authorization establishes factual entailment or answer faithfulness.

## Confirmed Design Decisions

1. **Migration ownership:** Flyway remains the sole owner of schema evolution. Python uses SQLAlchemy Core mappings/queries against the shared tables. Any required schema addition is an additive Flyway migration; Python must not run a competing Alembic history.
2. **Database startup:** Provide a Flyway-owned migration startup path usable before either backend. Verify Python against an empty database and a database already created/migrated by Java.
3. **Authentication:** Match the existing server-side session and CSRF semantics. Do not substitute stateless signed cookies. Any additional session persistence schema must be Flyway-owned and compatible with Java.
4. **Compose selection:** Use a documented alternate Compose override that replaces the `backend` service, leaving the frontend's `backend:8080` routing unchanged and ensuring only one public backend is active.
5. **Authorization semantics:** Match Java's role-permission, active-document, current-version, and active-index predicates in SQL. Do not invent Python-only classification filtering.
6. **Evaluation contract:** Preserve existing evaluation result fields, including diagnostics, ablations, and prefix-shadow metrics, as required by the frontend and stored schema.
7. **Trace contract:** First match the existing Java trace response and persistence behavior. Per-retriever timing fields are a separate additive API/schema change and must be implemented for both backends if approved.
8. **Seed recovery:** Include the seed-repair gap as a narrowly scoped shared Java/Python change. A seed-owned document with no active version after a transient failure may be retried in a bounded, observable manner; administrator-disabled/deleted documents must remain inactive.
9. **Held-out evaluation:** Freeze a paraphrase set separately from the development set before policy tuning. Never tune policy against held-out cases; report both sets independently.
10. **Dependencies:** Prefer Python 3.12+, `uv` with committed lockfile, FastAPI, Pydantic v2, SQLAlchemy Core and Psycopg 3, HTTPX adapters, pytest, Testcontainers, Ruff, and mypy or Pyright. Start with no LangChain or LlamaIndex dependency. Add LangChain only for a demonstrated, isolated model/embedding adapter need; do not add LlamaIndex absent a specific, justified isolated benefit.

## Proposed Python Layout

```text
python-backend/
  pyproject.toml
  uv.lock
  Dockerfile
  app/
    main.py
    settings.py
    api/                 # Thin transport handlers and response models
    security/            # Trusted identity, server-side session, CSRF
    database/            # Connections, schema mappings, transaction helpers
    documents/           # Domain service, repositories, lifecycle
    ingestion/           # Validation, parsers, chunking, embeddings
    retrieval/           # ACL SQL, identifiers, fusion, evidence policy
    generation/          # Providers, isolated context, citation validation
    evaluation/          # Dataset loading, deterministic runner, metrics
    observability/       # Trace persistence/API and low-cardinality metrics
  tests/
    unit/
    integration/
    contract/
```

The module layout is a starting point. Keep responsibilities cohesive, avoid oversized modules, and enforce boundaries with tests or static checks where useful. Routes must not own domain behavior or authorization policy.

## Compatibility and Security Invariants

### Identity and API

- The server maps only the known demo identities to their fixed roles and capabilities; unknown identities are rejected.
- Roles are never accepted as trusted client input.
- Preserve session cookie, CSRF, route, JSON, multipart, status, and structured error behavior used by the frontend.
- Direct source and trace reads independently authorize the caller; possession of an identifier is not authorization.

### Ingestion and Version Lifecycle

Preserve the lifecycle:

```text
upload
-> validate
-> reserve staged version
-> parse and chunk
-> generate embeddings
-> persist staged chunks
-> atomically activate
-> supersede prior active version
```

- A failed reindex must not replace a healthy active version.
- Reserve/activate operations must use database transactions and PostgreSQL locking; process-local locks are insufficient.
- Version numbering must serialize deterministically for concurrent first uploads.
- Identical content with metadata/permission changes should avoid unnecessary embedding generation.
- Upload/reindex must not implicitly restore a disabled or deleted document; restore remains explicit.
- Disable/delete and permission changes must be reflected immediately by retrieval and source resolution.
- Parsing or chunking differences from Java must be documented and covered with deterministic golden tests.

### Retrieval and Evidence

- Every vector, full-text, and exact-identifier retrieval SQL path filters by server-derived roles, active document status, current active version, and active index status before rows can enter ranking or fusion.
- Use parameterized SQL. Do not retrieve globally and filter in Python after ranking.
- Use exact pgvector search for the current small demo corpus unless approximate indexing is separately justified and filtered-recall behavior is tested.
- Preserve exact identifier boundaries, RRF ordering/deduplication, retrieval diagnostics, and evidence-gate rules/reasons.
- Runtime chat and offline evaluation use equivalent deterministic evidence policy. Weak-evidence hard negatives must guard against false admissions.
- Insufficient evidence yields no citations and no retrieved source context to generation.

### Citations and Generation

- Retrieved document text is untrusted data. Keep system instructions structurally separate, encode/escape delimiters, and add adversarial tests. Do not claim prompt-injection immunity.
- Validate generated/source identifiers against supplied evidence, then reauthorize each citation against current document status, active version, and caller roles.
- Citation validity means the source exists and is currently authorized; it does not prove a generated claim is entailed or factually correct.
- Provide DeepSeek, disabled, and deterministic test providers. Secrets come only from environment/secret injection and never enter logs.
- Bound connect/read/total timeouts and retry transient failures only. Do not retry deterministic validation/authentication failures.
- When generation is disabled but evidence is sufficient, clearly label retrieval-only output and preserve reauthorized citations. Insufficient evidence still returns no citations.

### Evaluation and Observability

- Reuse the current dataset labels and category logic without relabeling cases to improve scores or hardcoding expected answers.
- Acceptance thresholds are at least as strict as Java's documented release thresholds: zero ACL leakage, 100% freshness, 100% citation-ID validity, false-abstention at most 20%, and false-admission at most 5%.
- Keep held-out results separate from development results.
- Keep raw queries and retrieved text out of persisted traces. Use query hashes, bounded retention, and low-cardinality metric labels; avoid query/user-provided text, document IDs, and trace IDs as metric labels.
- Preserve trace access scope and response shape. Telemetry persistence failure must not silently leak data or incorrectly replace a successful chat response unless that behavior is explicitly changed and tested.

## Delivery Phases

### M0: Compatibility Audit and Design Lock

- Finalize API, schema, Java behavior, frontend dependency, dataset, and Compose inventories.
- Record the existing Java evaluation baseline and identify a repeatable isolated database/environment for comparison.
- Specify server-session/CSRF behavior, migration startup mechanism, Compose override, and trace contract scope.
- Define normalized contract fixtures for identities, errors, upload validation, chat, source authorization, lifecycle operations, evaluation, and trace responses.
- Freeze the held-out paraphrase evaluation data before evidence-policy implementation/tuning.
- Specify and test the shared seed-repair state transition before making the Java behavior change.

### M1: Python Foundation

- Add pinned dependencies/lockfile, Ruff, static typing, pytest/coverage, and dependency scanning.
- Implement FastAPI startup/settings, health endpoints, structured error handling, PostgreSQL connectivity, and mappings for the Flyway schema.
- Implement trusted demo identity, server-side session, and CSRF behavior.
- Add Flyway-owned migration startup and alternate Compose configuration.
- Verify startup against both an empty database and an existing Java-created schema.

### M2: Ingestion and Lifecycle

- Implement PDF, Markdown, and TXT validation/parsing, content hashing, normalized metadata, and deterministic chunking with page/heading metadata where supported.
- Add Ollama embedding adapter and validate returned model/dimension behavior.
- Implement staged version reservation, transactional activation, atomic supersession, duplicate-content metadata/permission updates, disable/delete/restore, and concurrency controls.
- Implement bounded seed retry/repair without reactivating admin-disabled/deleted content; make outcomes observable and seeding idempotent.
- Add real PostgreSQL/pgvector Testcontainer coverage for lifecycle, failures, concurrency, and seed repair.

### M3: Secure Retrieval and Generation

- Implement SQL ACL-filtered vector, PostgreSQL FTS, and exact-identifier search; apply authorization before candidate ranking/fusion.
- Implement deterministic RRF, deduplication, diagnostics, evidence gate, and retrieval-only response behavior.
- Add DeepSeek, disabled, and test provider adapters with classified retry behavior and secret-safe traces/logs.
- Implement isolated prompt context and citation-ID validation plus database reauthorization.
- Add adversarial prompt tests and integration tests for ACL-before-fusion, source denial, version freshness, and disable/delete effects.

### M4: Evaluation and Portfolio Parity

- Port the complete deterministic evaluation behavior and response contract, including diagnostic/ablation/prefix-shadow fields.
- Run the existing development dataset and separately frozen held-out paraphrase set; report each independently against release thresholds.
- Add Java/Python normalized contract tests for representative auth, documents, uploads, chat, sources, lifecycle, evaluation, and traces.
- Run existing Playwright workflows against Python, adding a direct unauthorized source-preview assertion if current browser coverage does not exercise that contract.
- Complete both Compose workflows, including health checks, deterministic Ollama model initialization, cold start, and teardown-on-failure.
- Write `docs/python-backend.md` and `docs/backend-parity.md`; update README with a neutral implementation comparison and verified status only.
- Review security invariants, known differences, production limitations, and secret/data hygiene before delivery.

## Verification and Acceptance

The feature is complete only when all required checks below have been run and their outcomes reported accurately:

- **Java:** `cd backend && ./mvnw verify` under Java 21; preserve all existing quality gates and coverage thresholds.
- **Frontend:** `cd frontend && npm ci && npm run build && npm test -- --run && npm audit --audit-level=moderate`.
- **Python:** Ruff format/lint, static type check, unit tests, integration tests, contract tests, and configured coverage threshold.
- **PostgreSQL:** real PostgreSQL/pgvector Testcontainers. SQLite is not a substitute for integration tests.
- **Database compatibility:** start Python from an empty database using Flyway ownership and against a database already migrated/used by Java.
- **API parity:** compare normalized responses from both backends, normalizing only nondeterministic UUIDs, timestamps, latency, and trace IDs.
- **Security:** ACL leakage zero; SQL filtering before fusion; source reauthorization; active-version freshness; immediate disable/delete effects; explicit restore; no citations for insufficient evidence.
- **Evaluation:** freshness and citation-ID validity 100%; false-abstention no more than 20%; false-admission no more than 5%; held-out metrics independently reported.
- **Browser:** existing Playwright login/chat/citation/source preview/upload/version/trace/evaluation/error flows pass against Python, including unauthorized source denial.
- **Compose:** Java default and Python override each start with one backend bound to the public backend port; PostgreSQL/Ollama are reused, health checks succeed, and E2E teardown works on success and failure.
- **Documentation and security review:** clearly label implemented, locally verified, optional live-provider, and production-limitation claims; no credentials, private traces, customer data, or private evaluation outputs are committed.

Do not report an unrun check as passed. If Docker or another infrastructure dependency is unavailable, state which tests were skipped and why.

## Known Differences and Risks to Track

- PDF parsing can differ between Java PDFBox and a Python parser. Compare extracted text and chunk boundaries using synthetic fixtures; document residual differences rather than claiming exact parity.
- Session/CSRF and login/logout behavior are easy to approximate incorrectly; contract tests must cover session rotation, CSRF rejection, and logout invalidation.
- Existing evaluation persistence has evolved through multiple Flyway versions. Map and return all fields required by the UI; do not infer schema from only the initial migrations.
- The current character chunker is part of observed behavior; a token-based replacement would be a behavior change and is not in scope.
- The Java seed failure path can leave a first-time failed fixture inactive, after which normal seeding skips it. The shared repair must distinguish transient seed-owned failures from deliberate administrative deactivation.
- A separate held-out set can be contaminated by policy tuning. Keep access/use separate during development and preserve its provenance/version.
- Current demo authentication, application-level SQL ACLs (without PostgreSQL RLS), synthetic calibrated policy, local Compose defaults, and citation checks remain portfolio-MVP limitations.
- DeepSeek live generation is optional and cannot be a deterministic CI requirement. Its live behavior must be labeled unverified unless explicitly smoke-tested with an injected secret.

## Required Final Report

At implementation completion, report:

1. Files added and changed.
2. Final architecture and Flyway/schema ownership.
3. LangChain usage and whether LlamaIndex was used, with rationale.
4. API compatibility result and known differences.
5. Empty-database and Java-created-database compatibility results.
6. Java quality-gate results.
7. Python unit, integration, and contract test results.
8. Frontend and Playwright results.
9. Compose cold-start, health, and teardown results.
10. Development and held-out deterministic evaluation metrics.
11. Optional live-provider verification status.
12. Remaining limitations and unresolved risks.
13. Exact commands used to reproduce the checks.
