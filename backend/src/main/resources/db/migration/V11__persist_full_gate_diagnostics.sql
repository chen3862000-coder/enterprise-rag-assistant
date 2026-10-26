ALTER TABLE evaluation_scenario_results
    ADD COLUMN gate_diagnostics_detail jsonb NOT NULL DEFAULT '{}'::jsonb;
