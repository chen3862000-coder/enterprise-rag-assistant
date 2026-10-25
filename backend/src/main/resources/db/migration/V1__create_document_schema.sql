CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE documents (
    id text PRIMARY KEY,
    title text NOT NULL,
    department text NOT NULL,
    classification text NOT NULL CHECK (classification IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')),
    current_version_id uuid,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED', 'DELETED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE document_versions (
    id uuid PRIMARY KEY,
    document_id text NOT NULL REFERENCES documents(id),
    version_number integer NOT NULL CHECK (version_number > 0),
    content_hash char(64) NOT NULL,
    source_filename text NOT NULL,
    effective_at timestamptz,
    index_status text NOT NULL CHECK (index_status IN ('INDEXING', 'ACTIVE', 'SUPERSEDED', 'FAILED')),
    embedding_model_id text NOT NULL,
    embedding_dimensions integer NOT NULL CHECK (embedding_dimensions > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_id, version_number),
    CHECK (embedding_dimensions = 768)
);

ALTER TABLE documents
    ADD CONSTRAINT documents_current_version_fk
    FOREIGN KEY (current_version_id) REFERENCES document_versions(id) DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX document_versions_document_idx ON document_versions(document_id, version_number DESC);
CREATE INDEX document_versions_content_hash_idx ON document_versions(document_id, content_hash);
CREATE INDEX documents_status_idx ON documents(status);

CREATE TABLE document_chunks (
    id text PRIMARY KEY,
    document_version_id uuid NOT NULL REFERENCES document_versions(id) ON DELETE CASCADE,
    chunk_index integer NOT NULL CHECK (chunk_index >= 0),
    chunk_text text NOT NULL,
    heading_path text,
    page_number integer,
    embedding vector(768) NOT NULL,
    search_vector tsvector GENERATED ALWAYS AS (to_tsvector('english', coalesce(heading_path, '') || ' ' || chunk_text)) STORED,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_version_id, chunk_index)
);

CREATE INDEX document_chunks_version_idx ON document_chunks(document_version_id);
CREATE INDEX document_chunks_search_idx ON document_chunks USING gin(search_vector);

CREATE TABLE document_permissions (
    document_id text NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    allowed_role text NOT NULL,
    PRIMARY KEY (document_id, allowed_role)
);

CREATE TABLE ingestion_jobs (
    id uuid PRIMARY KEY,
    document_id text NOT NULL REFERENCES documents(id),
    document_version_id uuid NOT NULL REFERENCES document_versions(id),
    status text NOT NULL CHECK (status IN ('INDEXING', 'SUCCEEDED', 'FAILED')),
    error_message text,
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);

CREATE INDEX ingestion_jobs_document_idx ON ingestion_jobs(document_id, started_at DESC);
CREATE UNIQUE INDEX ingestion_jobs_one_active_per_document_idx
    ON ingestion_jobs(document_id) WHERE status = 'INDEXING';
