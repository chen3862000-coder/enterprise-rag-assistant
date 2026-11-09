# Evaluation Design

The development dataset `evals/dataset.jsonl` is versioned `2026-10-p1-v5` and contains 63 scenarios: 12 fact lookup, 8 multi-document, 8 exact/keyword, 12 permission-isolation, 8 unanswerable, 6 freshness, and 9 hard negatives. The separately versioned held-out dataset `python-backend/evals/heldout-paraphrases.jsonl` contains 40 cases spanning English, Chinese, mixed-script, CSD paraphrases, ACL negatives, and hard negatives. IDs are disjoint. Every case labels gate behavior separately from expected document IDs. Freshness expectations are minimum active-version baselines.

## Offline Evaluation

Carol can start offline evaluation from the backend or UI. It uses fixed server-defined user role scopes and the PostgreSQL-backed hybrid retriever. Java persists development and held-out reports as two independent runs with separate dataset versions and metrics; no combined metric is presented as either dataset's result. Python stores its compatibility run and keeps the dataset metric groups separate in run configuration. Evaluation does not call answer generation or a judge model. Java forces deterministic query planning by default for evaluation and records the planner execution mode; explicitly configured planning is bounded and may call its provider. Persisted measures include:

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

The paired Java baseline reports completed on the local seeded corpus on 2026-11-08 using deterministic planning and no prefix-shadow run. These are **pre-parity-fix baselines**; the coverage/exact-path implementation changed afterward, so they are not current Java post-fix measurements:

| Dataset | Run ID | Scenarios | Recall@5 | MRR | Gate accuracy | False abstention | False admission | ACL leakage | Freshness | Citation-ID |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Development `2026-10-p1-v5` | `1ba68ff8-dd69-4c69-8fc8-eeed98d8a6e9` | 63 | 1.000 | 0.926 | 0.937 | 8.57% | 3.57% | 0 | 1.000 | 1.000 |
| Held-out `heldout-paraphrases-v3` | `a05b3306-7312-4c4c-a9a5-035cc50b51b7` | 40 | 1.000 | 0.863 | 0.700 | 46.15% | 0.00% | 0 | 1.000 | 1.000 |

The first cross-backend comparison also revealed that Python had calculated relevance from the post-gate context rather than pre-gate authorized candidates, undercounting false abstentions. The evaluator now keeps those populations separate, consistent with Java. Final Java reports use development run `8027a2fb-3886-4dde-88b8-80e8259f2afe` and held-out run `dea756bd-cc24-4ca2-a60f-b6d55fe8b8c1`: held-out R@5 1.000/MRR 0.894/gate accuracy 0.925, false abstention 11.54% (3/26), false admission 0%, zero ACL leakage. Corrected Python run `5e292531-531e-45b8-afdb-c2b5f7fbba8d` reports held-out R@5 1.000/MRR 0.923/gate accuracy 0.875, false abstention 19.23% (5/26), false admission 0%, zero ACL leakage. Java's three false abstentions are one request-ID lookup and two CSD paraphrases where no single candidate met all joint signals; Python's five are CSD/other paraphrases with weaker or split evidence. Reports use the same local seeded corpus and deterministic planner, but the inspected held-out set is exploratory, not blind release evidence. No thresholds were loosened. Java does not persist per-scenario baseline/rewrite candidate IDs, limiting causal planner attribution.

## Optional Future Live Evaluation

Live evaluation is deferred. A future live mode should be separately enabled, identify provider/model and dataset/retrieval configuration, label LLM-as-a-Judge values as model-generated judgments, and report measured values only. It should not replace deterministic ACL/freshness gates.
