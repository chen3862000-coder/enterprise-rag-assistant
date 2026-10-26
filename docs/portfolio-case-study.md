# Portfolio Case Study

## Problem

Enterprise knowledge is fragmented, changes over time, and is subject to document-level access restrictions. A relevant answer is not trustworthy if the evidence was stale, unauthorized, or impossible to verify.

## Engineering Challenges

- Apply authorization before retrieval candidates reach fusion or generation.
- Keep active document versions consistent when indexing fails or overlaps.
- Combine semantic and exact-language retrieval without mixing incomparable score scales.
- Return resolvable source-authorized citation IDs and deterministically reject evidence below the configured sufficiency gate.
- Measure retrieval, permission isolation, freshness, citation-ID validation, and evidence sufficiency without paid model calls.

## Solution

The MVP is a Spring Boot modular monolith backed by PostgreSQL/pgvector. Role-scoped vector, PostgreSQL full-text, and exact-identifier SQL paths feed Reciprocal Rank Fusion. A shared evidence gate filters weak candidates before context assembly in chat and offline scoring. Ingestion stages document versions and activates them transactionally. Identical active content updates metadata/ACL without re-embedding, and permission revocation is reflected in subsequent retrieval queries. The React UI exposes citations, document lifecycle metadata, traces, and persisted offline evaluation runs.

## Demonstration

1. Ask an API policy question and inspect its current source version and trace.
2. Compare Alice/Bob/Carol role scopes and observe that inaccessible source evidence is unavailable.
3. Update a document's ACL without changing its content, confirm revocation takes effect immediately, then index a newer policy version and inspect active-version retrieval and offline evaluation results.

## Results

No business-impact or live answer-quality result is claimed here. Automated verification outcomes are recorded in the repository and generated JaCoCo report after each verification run. Offline evaluation reports retrieval, ACL, freshness, citation-ID validation, and shared evidence-gate measurements only; it does not score claim entailment or generated-answer faithfulness. Results are real only after a run against the selected database and embedding setup.

## Evidence

The implementation's relevant security evidence is concrete and testable: each vector/full-text/exact candidate SQL path joins the logical document ACL and active-version state before returning chunks; same-content uploads update the ACL transactionally without embedding work; and the shared evidence gate removes weak candidates before context assembly. `CITATIONS_PRESENT` reports authorized citation IDs only and must not be read as a claim-entailment judgment.

## Before / Design / Evidence / Limitations

- **Before:** employees must search fragmented documents manually and may use stale or inaccessible copies.
- **Design:** SQL filters role permissions and active versions before candidate fusion; ingestion stages and atomically activates versions; citation/source reads reauthorize against the current identity.
- **Evidence:** PostgreSQL integration tests exercise ACL isolation, revocation, concurrent first create, and version activation. The deterministic offline suite reports retrieval and gate diagnostics separately. These are implementation checks on synthetic data, not measured customer impact.
- **Limitations:** demo identity selection is not authentication, application SQL predicates are not PostgreSQL RLS, the deterministic gate is heuristic, citations are not entailment, and container defaults are for local demo use only.

## Tradeoffs

The demo uses fixed identities, exact vector search, local Ollama embeddings, and application-level SQL ACL predicates. Production deployment would require real identity/SSO, stronger secrets and audit handling, operational hardening, threat modeling, and infrastructure-specific reliability work.

## Business Value

The architecture demonstrates how a knowledge assistant can reduce search friction while preserving document permissions and making evidence inspectable. No time savings or business outcome is asserted without measurement.

## Demo Media

- Screenshots: [`knowledge workspace`](../frontend/docs/media/knowledge-workspace.png), [`persisted evaluation`](../frontend/docs/media/evaluation-workspace.png), and [`chat/source preview`](../frontend/docs/media/chat-source.png), captured from the local demo UI after the current worktree was deployed.
- Demo video: pending recording.
