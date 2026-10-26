# Evaluation Design

`evals/dataset.jsonl` is versioned `2026-10-p1-v5` and contains 63 scenarios: 12 fact lookup, 8 multi-document, 8 exact/keyword, 12 permission-isolation, 8 unanswerable, 6 freshness, and 9 hard negatives. The original 56 labels were preserved; seven adversarial and permission-scope scenarios were added. Every case explicitly labels gate behavior separately from expected document IDs. Freshness expectations are minimum active version baselines.

## Offline Evaluation

Carol can start an offline evaluation from the backend or UI. It uses fixed server-defined user role scopes and the PostgreSQL-backed hybrid retriever. It does not call the chat provider or a judge model. Persisted measures include:

- Recall@5 and MRR over scenarios with expected evidence.
- Retrieval expected-document recall/miss lists are reported separately from the scenario contract pass flag.
- Count of forbidden documents returned by the retrieval pipeline.
- Active-version freshness correctness where a fixture specifies the expected version.
- Citation-ID validator behavior on deterministic candidate evidence. The metric is citation ID validation, not claim entailment or citation faithfulness.
- Accuracy of the shared runtime evidence-sufficiency gate against explicit answerable/unanswerable and hard-negative expectations, with gate reason, candidate-level score diagnostics, and retrieval configuration persisted per scenario/run.
- Diagnostic-only gate ablations compare the current same-chunk vector+OR-lexical+coverage policy, vector-only, lexical-coverage-only, and 0.30/0.40 coverage variants. They report true passes and both error types; they do not change runtime behavior.
- Release quality requires zero ACL leakage, at least 10 relevant-positive and 10 negative cases, false-abstention rate ≤20%, and false-admission rate ≤5%. Historical runs lacking candidate-outcome labels expose these new rates as unavailable rather than zero.
- The paired Nomic prefix shadow embeds SQL-authorized active chunks using `search_document:` and scenario queries using `search_query:` in memory, then evaluates dense top-10 retrieval and the diagnostic vector-only gate. It is isolated from the current hybrid retrieval index and chat path. The stored run labels this variant explicitly; historical runs report the shadow metric as unavailable.

False-abstention rate uses answerable fixtures only when an expected document reached authorized candidate retrieval. False-admission rate includes explicit abstention cases and answerable fixtures whose expected candidate did not reach the gate. Gate accuracy is fixture-based, not LLM abstention or answer correctness. Recall/MRR remain separate retrieval metrics. Diagnostics contain numeric signals/counts and expected-document match indicators, not source text. Citation-ID validation does not verify claim support.

## Latest Local Measurement

The v5 run `2ce9ea94-fed7-4acf-aa5b-ec2c558d73c5` completed successfully against 63 scenarios: Recall@5 1.000, MRR 0.912, ACL leakage 0, freshness accuracy 1.000, evidence-gate accuracy 0.937, false-abstention rate 11.4%, and false-admission rate 0.0%. The persisted release-quality flag is `PASS`. Four relevant authorized candidates were false-abstained (`rag-005`, `rag-020`, `rag-028`, and `rag-051`); there were no false admissions. This is a local synthetic-corpus measurement, not generated-answer correctness or a CI result.

## Optional Future Live Evaluation

Live evaluation is deferred. A future live mode should be separately enabled, identify provider/model and dataset/retrieval configuration, label LLM-as-a-Judge values as model-generated judgments, and report measured values only. It should not replace deterministic ACL/freshness gates.
