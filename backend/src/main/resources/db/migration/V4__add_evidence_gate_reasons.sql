ALTER TABLE evaluation_scenario_results
    ADD COLUMN gate_reason text NOT NULL DEFAULT 'UNRECORDED';
