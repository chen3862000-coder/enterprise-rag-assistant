CREATE TABLE python_sessions (
    session_id text PRIMARY KEY,
    username text,
    csrf_token text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL
);

CREATE INDEX python_sessions_expiry_idx ON python_sessions(expires_at);
