# Evaluation Design

`evals/dataset.jsonl` is versioned `2026-10-p1-v3` and contains 56 scenarios: 12 fact lookup, 8 multi-document, 8 exact/keyword, 10 permission-isolation, 8 unanswerable, 6 freshness, and 4 hard negatives. Each case separates `shouldPassEvidenceGate` from whether expected documents exist; this is important for permission-limited questions where the user lacks the expected document but might still retrieve weak public candidates. The synthetic seeder indexes the archived rate-limit policy as logical document version 1 and current policy as active version 2. Freshness expectations are minimum active version baselines, so a later successful reindex remains fresh.

## Offline Evaluation

Carol can start an offline evaluation from the backend or UI. It uses fixed server-defined user role scopes and the PostgreSQL-backed hybrid retriever. It does not call the chat provider or a judge model. Persisted measures include:

- Recall@5 and MRR over scenarios with expected evidence.
- Retrieval expected-document recall/miss lists are reported separately from the scenario contract pass flag.
- Count of forbidden documents returned by the retrieval pipeline.
- Active-version freshness correctness where a fixture specifies the expected version.
- Citation-ID validator behavior on deterministic candidate evidence. The metric is citation ID validation, not claim entailment or citation faithfulness.
- Accuracy of the shared runtime evidence-sufficiency gate against answerable/unanswerable and hard-negative expectations, with gate reason persisted per scenario and retrieval depth, RRF constant, and gate thresholds persisted per run.

The evidence-gate metric measures the actual deterministic gate used by chat and offline evaluation against fixture expectations; it is not LLM abstention accuracy. Permission-isolation fixtures explicitly expect a closed gate even when unrelated public candidates exist. Scenario pass status checks ACL leakage, expected version, supplied citation-ID behavior, and gate decision. Recall/MRR and missing expected documents are computed from authorized fused candidates before evidence-gate filtering, so retrieval relevance remains separate from the gate decision. Citation ID validation does not verify claim support. Neither metric is generated-answer correctness or faithfulness, so offline results must not be presented as an answer quality benchmark.

## Optional Future Live Evaluation

Live evaluation is deferred. A future live mode should be separately enabled, identify provider/model and dataset/retrieval configuration, label LLM-as-a-Judge values as model-generated judgments, and report measured values only. It should not replace deterministic ACL/freshness gates.
