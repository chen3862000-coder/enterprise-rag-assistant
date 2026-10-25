# Evaluation Design

`evals/dataset.jsonl` is the versioned deterministic fixture for 52 scenarios: 12 fact lookup, 8 multi-document, 8 exact/keyword, 10 permission-isolation, 8 unanswerable, and 6 freshness cases. The synthetic seeder indexes the archived rate-limit policy as logical document version 1 and the current policy as active version 2; scenarios for the current policy expect version 2 as a minimum active version. Reindexing the current content can increment that active version while remaining fresh.

## Offline Evaluation

Carol can start an offline evaluation from the backend or UI. It uses fixed server-defined user role scopes and the PostgreSQL-backed hybrid retriever. It does not call the chat provider or a judge model. Persisted measures include:

- Recall@5 and MRR over scenarios with expected evidence.
- Count of forbidden documents returned by the retrieval pipeline.
- Active-version freshness correctness where a fixture specifies the expected version.
- Citation-ID validator behavior on deterministic candidate evidence.
- Evidence-based abstention policy correctness.

The citation and abstention fields are logic checks, not generated-answer correctness, faithfulness, or answer-level abstention accuracy. Offline results must not be presented as an answer quality benchmark.

## Optional Future Live Evaluation

Live evaluation is deferred. A future live mode should be separately enabled, identify provider/model and dataset/retrieval configuration, label LLM-as-a-Judge values as model-generated judgments, and report measured values only. It should not replace deterministic ACL/freshness gates.
