ALTER TABLE evaluation_runs
    ADD COLUMN evidence_gate_false_abstention_rate double precision,
    ADD COLUMN evidence_gate_false_admission_rate double precision,
    ADD COLUMN release_quality_passed boolean;

ALTER TABLE evaluation_scenario_results
    ADD COLUMN expected_relevant_candidate boolean;

ALTER TABLE rag_traces
    ADD COLUMN response_mode text NOT NULL DEFAULT 'GENERATED';
