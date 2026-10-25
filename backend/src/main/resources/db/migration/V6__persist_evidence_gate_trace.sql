ALTER TABLE rag_traces
    ADD COLUMN evidence_sufficient boolean NOT NULL DEFAULT false,
    ADD COLUMN evidence_gate_reason text NOT NULL DEFAULT 'UNRECORDED';
