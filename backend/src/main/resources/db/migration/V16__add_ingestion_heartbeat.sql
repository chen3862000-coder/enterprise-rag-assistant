ALTER TABLE ingestion_jobs
    ADD COLUMN heartbeat_at timestamptz NOT NULL DEFAULT now();

CREATE INDEX ingestion_jobs_stale_heartbeat_idx
    ON ingestion_jobs(heartbeat_at)
    WHERE status = 'INDEXING';
