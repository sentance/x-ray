CREATE TABLE paper_session (
    id UUID PRIMARY KEY,
    state JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE paper_evidence (
    seq BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES paper_session(id),
    received_at BIGINT NOT NULL,
    type TEXT NOT NULL,
    payload JSONB NOT NULL
);
CREATE INDEX paper_evidence_session_seq ON paper_evidence(session_id, seq);
