ALTER TABLE ingestion_jobs
    ADD COLUMN source_path text,
    ADD COLUMN source_filename text,
    ADD COLUMN source_size_bytes bigint,
    ADD COLUMN progress_chunks integer NOT NULL DEFAULT 0,
    ADD COLUMN progress_total_chunks integer,
    ADD COLUMN worker_id text,
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN queued_at timestamptz NOT NULL DEFAULT now();

CREATE INDEX ingestion_jobs_queue_idx
    ON ingestion_jobs(queued_at, started_at)
    WHERE status = 'INDEXING';
