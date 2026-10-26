ALTER TABLE evaluation_runs
    ADD COLUMN prefix_shadow_metrics jsonb NOT NULL DEFAULT '{}'::jsonb;
