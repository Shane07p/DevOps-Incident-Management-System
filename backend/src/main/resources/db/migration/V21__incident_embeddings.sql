ALTER TABLE incident
    ADD COLUMN embedding incident.vector(768),
    ADD COLUMN embedding_attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN embedding_retry_at timestamptz DEFAULT now(),
    ADD CONSTRAINT incident_embedding_attempts_check CHECK (embedding_attempts >= 0);

CREATE INDEX incident_embedding_retry_idx
    ON incident (org_id, embedding_retry_at, id)
    WHERE embedding IS NULL;
