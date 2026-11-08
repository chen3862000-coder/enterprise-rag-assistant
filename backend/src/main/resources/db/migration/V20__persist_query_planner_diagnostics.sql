ALTER TABLE rag_traces
    ADD COLUMN query_planner_provider varchar(32) NOT NULL DEFAULT 'none',
    ADD COLUMN query_planner_model varchar(100) NOT NULL DEFAULT 'none',
    ADD COLUMN query_planner_latency_ms integer NOT NULL DEFAULT 0,
    ADD COLUMN query_planner_status varchar(24) NOT NULL DEFAULT 'DISABLED',
    ADD COLUMN query_planner_fallback_reason varchar(40),
    ADD COLUMN query_planner_intent varchar(24) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN query_planner_generated_count integer NOT NULL DEFAULT 0,
    ADD COLUMN query_planner_deduplicated_count integer NOT NULL DEFAULT 0,
    ADD COLUMN query_planner_channel_counts jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN query_planner_candidate_count integer NOT NULL DEFAULT 0,
    ADD COLUMN query_planner_retrieval_counts jsonb NOT NULL DEFAULT '{}'::jsonb;
