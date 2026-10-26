ALTER TABLE evaluation_runs
    ADD COLUMN gate_ablation_accuracy jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE evaluation_scenario_results
    ADD COLUMN expected_evidence_gate boolean NOT NULL DEFAULT false,
    ADD COLUMN gate_diagnostics jsonb NOT NULL DEFAULT '{}'::jsonb;
