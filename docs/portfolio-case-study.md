# Portfolio Case Study

## Problem

Enterprise knowledge is fragmented, changes over time, and is subject to document-level access restrictions. A relevant answer is not trustworthy if the evidence was stale, unauthorized, or impossible to verify.

## Engineering Challenges

- Apply authorization before retrieval candidates reach fusion or generation.
- Keep active document versions consistent when indexing fails or overlaps.
- Combine semantic and exact-language retrieval without mixing incomparable score scales.
- Return resolvable citations and safely abstain when evidence is missing.
- Measure retrieval, permission isolation, freshness, and citation logic without paid model calls.

## Solution

The MVP is a Spring Boot modular monolith backed by PostgreSQL/pgvector. Role-scoped vector, PostgreSQL full-text, and exact-identifier SQL paths feed Reciprocal Rank Fusion. Ingestion stages document versions and activates them transactionally. The React UI exposes chat citations, document lifecycle metadata, traces, and persisted offline evaluation runs.

## Demonstration

1. Ask an API policy question and inspect its current source version and trace.
2. Compare Alice/Bob/Carol role scopes and observe that inaccessible source evidence is unavailable.
3. Index a newer policy version and inspect active-version retrieval and offline evaluation results.

## Results

No business-impact or live answer-quality result is claimed here. Automated verification outcomes are recorded in the repository and generated JaCoCo report after each verification run. Offline evaluation scores are real only after a run against the selected database and embedding setup.

## Tradeoffs

The demo uses fixed identities, exact vector search, local Ollama embeddings, and application-level SQL ACL predicates. Production deployment would require real identity/SSO, stronger secrets and audit handling, operational hardening, threat modeling, and infrastructure-specific reliability work.

## Business Value

The architecture demonstrates how a knowledge assistant can reduce search friction while preserving document permissions and making evidence inspectable. No time savings or business outcome is asserted without measurement.

## Demo Media

- Screenshot: pending capture.
- Demo video: pending recording.
