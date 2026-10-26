# Enterprise RAG Assistant

**Enterprise knowledge. Authorized retrieval. Inspectable sources.**

A portfolio MVP showing document-level authorization, active document versions, hybrid retrieval, source-authorized citations, deterministic evidence sufficiency, offline evaluation, and safe bounded traces. Citation IDs are source references, not claim-level verification. It demonstrates architecture patterns; it is **not a production-ready SaaS service**.

All Acme Enterprise documents are synthetic and safe to publish.

## What Is Implemented

- Markdown, text, and extractable-PDF ingestion; scanned PDFs/OCR are unsupported.
- Local Ollama embeddings with `nomic-embed-text` and PostgreSQL/pgvector persistence.
- Optional DeepSeek-backed answer generation, configured independently from embeddings.
- ACL-constrained vector, PostgreSQL full-text, and exact-identifier retrieval, fused with RRF.
- Server-issued demo sessions, role-scoped SQL retrieval, authorized source previews, and citations.
- Staged version indexing with transactionally activated chunks and version permissions.
- A 56-scenario deterministic offline evaluation. It measures retrieval, ACL, freshness, citation-ID validation, and the shared evidence-sufficiency gate; it does not measure generated-answer correctness.
- Bounded RAG traces, role-scoped trace APIs, 30-day default retention, and low-cardinality metrics.
- React chat, knowledge-management, and evaluation workspaces.

## Architecture

```mermaid
flowchart LR
  UI[React + Vite] -->|same-origin session REST| API[Spring Boot modular monolith]
  API --> SEC[Demo identity + CSRF]
  API --> ING[Ingestion + atomic versions]
  API --> RET[ACL SQL + vector / text / exact + RRF]
  API --> GEN[Optional DeepSeek generation + source-ID checks]
  API --> OBS[Evaluation + traces + metrics]
  ING --> DB[(PostgreSQL + pgvector)]
  RET --> DB
  OBS --> DB
  ING --> OLLAMA[Ollama embeddings]
  RET --> OLLAMA
  GEN --> DEEPSEEK[DeepSeek ChatModel]
```

More detail is in [`docs/architecture.md`](docs/architecture.md), [`docs/security-design.md`](docs/security-design.md), [`docs/retrieval-design.md`](docs/retrieval-design.md), and [`docs/evaluation-design.md`](docs/evaluation-design.md).

## Quick Start

Requirements: Docker Desktop with Compose, a supported JDK 21 for local Java checks, and a DeepSeek API key to generate answers. The first startup downloads the Ollama image/model and may take several minutes.

```bash
cp .env.example .env
# Edit .env and set DEEPSEEK_API_KEY for generated answers.
docker compose up --build -d
```

Open `http://localhost:3000`. The Compose stack runs PostgreSQL/pgvector, Ollama, one-time embedding-model setup, the Spring backend, and the React/Nginx frontend. Synthetic docs seed at backend startup. Ollama pulls `${EMBEDDING_MODEL:-nomic-embed-text}` into its persistent volume.

The app login is a demo identity selector, not password authentication. Compose defaults `CHAT_MODEL_PROVIDER=none`, so it starts without paid credentials; chat still runs retrieval and the evidence gate, but generated prose is disabled. Set `DEEPSEEK_API_KEY` and `CHAT_MODEL_PROVIDER=deepseek` in `.env` to enable generation. Offline evaluation and tests do not require the key.

### Local Backend Development

With PostgreSQL and Ollama available at the configured URLs:

```bash
cd backend
./mvnw spring-boot:run
```

The default seed path is `../synthetic-docs`; set `APP_SEED_ENABLED=true` and `APP_SEED_PATH` as needed. Pull the local embedding model once with `ollama pull nomic-embed-text` if Ollama does not already have it.

## Developer Commands

| Command | Purpose |
|---|---|
| `make up` | Build and start the Compose stack |
| `make down` | Stop the stack (preserves named volumes) |
| `make logs` | Follow service logs |
| `make seed` | Start/rebuild the backend with startup seeding enabled |
| `make test` | Run backend tests, Testcontainers, analysis, and coverage gates |
| `make frontend-test` | Run React component tests |
| `make eval` | Sign in as Carol and run the offline 56-scenario suite (requires `jq`) |

Backend quality gate, on Java 21:

```bash
cd backend && ./mvnw verify
```

This runs unit tests, PostgreSQL/pgvector Testcontainers integration tests, PMD, SpotBugs, ArchUnit, and JaCoCo class/method coverage thresholds. JaCoCo reports are written under `backend/target/site/jacoco/`. Frontend checks:

```bash
cd frontend && npm ci && npm run build && npm test -- --run && npm audit --audit-level=moderate
```

## Demo Identities

| Identity | Retrieval roles | Management/evaluation |
|---|---|---|
| Alice | Public, Engineering | No |
| Bob | Public, HR | No |
| Carol | Public, Security | Explicit document admin |

Carol’s document-management capability is separate from her retrieval scope. She can manage document metadata and evaluations, but only public/security evidence is eligible for her chat. Changing client-side role fields cannot grant access.

## Demo Scenarios

1. **Authorized evidence retrieval:** ask “What is the Enterprise API rate limit, and how should clients handle HTTP 429?” Inspect the gate reason, source references, and trace. Source IDs do not prove factual entailment.
2. **Permission isolation:** compare Alice and Bob on an HR-only policy. Alice should not receive HR evidence; direct source resolution independently applies access checks.
3. **Freshness/evaluation:** sign in as Carol, upload a newer policy under its logical document ID, then query again and run the offline evaluation. Inspect the active version and actual persisted results.

## API Overview

- `GET /api/auth/csrf`, `POST /api/auth/login`, `GET /api/auth/me`, `POST /api/auth/logout`
- `POST /api/chat`
- `GET /api/sources/{chunkId}`
- `GET /api/documents`, `POST /api/documents`, `POST /api/documents/{id}/reindex`, `GET /api/documents/{id}/versions`, `POST /api/documents/{id}/disable`, `DELETE /api/documents/{id}` (soft delete)
- `POST /api/evaluations/run`, `GET /api/evaluations`, `GET /api/evaluations/{runId}`
- `GET /api/traces/{traceId}`

Mutating browser requests require the session CSRF token. Document mutation and evaluation endpoints require Carol’s explicit admin capability. Ordinary document list/version metadata is role filtered; trace detail is owner/admin scoped.

Example chat request after login:

```json
{"message":"What is the Enterprise API rate limit?"}
```

Example response fields include `answer`, resolvable `citations`, `traceId`, and `groundingStatus`. `CITATIONS_PRESENT` means authorized source IDs were attached; it does not mean claims were independently verified. The deterministic evidence gate returns `INSUFFICIENT_EVIDENCE` and no citations for weak/unsupported evidence.

## Evaluation and Observability

The offline dataset version is `2026-10-p1-v4` (56 scenarios: baseline cases plus 4 hard negatives). Offline results use retrieval only and do not call DeepSeek. Recall@5/MRR and missing expected documents report retrieval relevance; ACL leakage, freshness, citation-ID validation, and shared evidence-gate accuracy report separate contract checks. Each fixture explicitly labels expected gate behavior. Evaluation also reports candidate-level vector/keyword diagnostics and current/vector-only/keyword-only/relaxed-threshold rule ablations; ablations are diagnostic and never alter runtime chat behavior. A separately labeled Nomic prefix shadow experiment embeds currently ACL-authorized chunks with `search_document:` and queries with `search_query:` in memory; it does not modify the active index or chat path. Citation IDs do not prove entailment, and evidence-gate accuracy is not LLM abstention or answer correctness. Thresholds are calibrated heuristics for this synthetic corpus, not universal guarantees.

Traces store a query hash and bounded timing/count/provider/status fields, not raw queries or retrieved text. Trace retention defaults to 30 days. Micrometer metrics avoid user/document/query labels. Chat persistence of a trace is best-effort so a telemetry write issue does not replace a successful answer with a failed request.

## Verification Status

Run the required backend quality gate on Java 21 using `cd backend && ./mvnw verify`; frontend checks use `cd frontend && npm ci && npm run build && npm test -- --run`. Earlier results are historical and do not verify a modified worktree. Validate Compose using `docker compose config --quiet`.

Earlier Compose measurements are historical. Run the current suite with `make eval` and report its persisted results. It measures retrieval/evidence-gate behavior, not generated-answer quality.

No live DeepSeek generation smoke test or browser-driven local functional test has been completed. The stack is currently configured with chat generation disabled (`CHAT_MODEL_PROVIDER=none`) and returns authorized citation IDs for inspection without a paid key. Do not infer generated-answer benchmark results from the offline suite.

## Limitations

- Demo login is an identity selector without passwords or SSO.
- ACLs are enforced in application SQL; PostgreSQL RLS is not configured.
- Local demo defaults, Compose credentials, prompt text, and citation validation are not a production security boundary.
- No DLP, OCR, connectors, distributed indexing queue, approximate-vector index, Kubernetes, live judge, or generated-answer benchmark. Gate thresholds are MVP heuristics calibrated on this small synthetic corpus; they can false-abstain or admit coincidental term/vector matches on other corpora.
- Structured JSON logging and full request-stage trace spans are not implemented.
- Docker Hub availability and first-time model downloads are external setup dependencies.

## Portfolio Case Study

See [`docs/portfolio-case-study.md`](docs/portfolio-case-study.md). Screenshot and demo-video placeholders remain until actual captures are made.

## Future Work

After M4, improve retrieval/evaluation tests, add explicitly enabled live answer evaluation and clearly labeled LLM-as-a-Judge results, then perform provider smoke tests and local functional testing. Production work would additionally require real identity integration, operational hardening, security review, and deployment-specific controls.
