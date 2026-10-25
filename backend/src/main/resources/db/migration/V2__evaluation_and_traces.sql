CREATE TABLE rag_traces (
    id uuid PRIMARY KEY,
    user_id text NOT NULL,
    query_hash char(64) NOT NULL,
    retrieval_duration_ms bigint NOT NULL CHECK (retrieval_duration_ms >= 0),
    generation_duration_ms bigint NOT NULL CHECK (generation_duration_ms >= 0),
    total_duration_ms bigint NOT NULL CHECK (total_duration_ms >= 0),
    vector_candidate_count integer NOT NULL CHECK (vector_candidate_count >= 0),
    keyword_candidate_count integer NOT NULL CHECK (keyword_candidate_count >= 0),
    exact_candidate_count integer NOT NULL CHECK (exact_candidate_count >= 0),
    context_chunk_count integer NOT NULL CHECK (context_chunk_count >= 0),
    citation_count integer NOT NULL CHECK (citation_count >= 0),
    model_provider text NOT NULL,
    model_id text NOT NULL,
    grounding_status text NOT NULL,
    status text NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    error_category text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX rag_traces_user_created_idx ON rag_traces(user_id, created_at DESC);
CREATE INDEX rag_traces_created_idx ON rag_traces(created_at DESC);

CREATE TABLE evaluation_runs (
    id uuid PRIMARY KEY,
    requested_by text NOT NULL,
    dataset_version text NOT NULL,
    evaluation_method text NOT NULL CHECK (evaluation_method IN ('OFFLINE_RETRIEVAL')),
    embedding_model_id text NOT NULL,
    retrieval_configuration jsonb NOT NULL,
    scenario_count integer NOT NULL CHECK (scenario_count >= 0),
    recall_at_5 double precision NOT NULL CHECK (recall_at_5 BETWEEN 0 AND 1),
    mean_reciprocal_rank double precision NOT NULL CHECK (mean_reciprocal_rank BETWEEN 0 AND 1),
    acl_leakage_count integer NOT NULL CHECK (acl_leakage_count >= 0),
    freshness_accuracy double precision NOT NULL CHECK (freshness_accuracy BETWEEN 0 AND 1),
    citation_logic_accuracy double precision NOT NULL CHECK (citation_logic_accuracy BETWEEN 0 AND 1),
    abstention_accuracy double precision NOT NULL CHECK (abstention_accuracy BETWEEN 0 AND 1),
    status text NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    started_at timestamptz NOT NULL,
    completed_at timestamptz NOT NULL
);

CREATE INDEX evaluation_runs_started_idx ON evaluation_runs(started_at DESC);

CREATE TABLE evaluation_scenario_results (
    id bigserial PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES evaluation_runs(id) ON DELETE CASCADE,
    scenario_id text NOT NULL,
    category text NOT NULL,
    expected_document_ids jsonb NOT NULL,
    retrieved_document_ids jsonb NOT NULL,
    missing_document_ids jsonb NOT NULL,
    forbidden_document_ids jsonb NOT NULL,
    expected_version integer,
    retrieved_versions jsonb NOT NULL,
    reciprocal_rank double precision NOT NULL CHECK (reciprocal_rank BETWEEN 0 AND 1),
    recall_at_5 double precision NOT NULL CHECK (recall_at_5 BETWEEN 0 AND 1),
    acl_leakage boolean NOT NULL,
    freshness_correct boolean NOT NULL,
    citation_logic_correct boolean NOT NULL,
    abstention_correct boolean NOT NULL,
    passed boolean NOT NULL,
    UNIQUE (run_id, scenario_id)
);

CREATE INDEX evaluation_results_run_idx ON evaluation_scenario_results(run_id, scenario_id);
