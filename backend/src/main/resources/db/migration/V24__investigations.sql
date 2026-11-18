-- V24: Investigation tables for AI SRE (Issue #242)

CREATE TABLE investigation (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id          BIGINT NOT NULL REFERENCES organization(id),
    incident_id     BIGINT NOT NULL REFERENCES incident(id),
    summary         TEXT   NOT NULL,
    status          VARCHAR(40) NOT NULL DEFAULT 'completed',
    steps_taken     INT    NOT NULL DEFAULT 0,
    tokens_used     INT    NOT NULL DEFAULT 0,
    duration_ms     DOUBLE PRECISION NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_investigation_incident ON investigation(incident_id);
CREATE INDEX idx_investigation_org      ON investigation(org_id);

CREATE TABLE investigation_hypothesis (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    investigation_id  BIGINT NOT NULL REFERENCES investigation(id) ON DELETE CASCADE,
    rank              INT    NOT NULL,
    title             VARCHAR(500) NOT NULL,
    confidence        DOUBLE PRECISION NOT NULL,
    reasoning         TEXT
);

CREATE INDEX idx_inv_hyp_investigation ON investigation_hypothesis(investigation_id);

CREATE TABLE investigation_evidence (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hypothesis_id BIGINT NOT NULL REFERENCES investigation_hypothesis(id) ON DELETE CASCADE,
    kind          VARCHAR(100) NOT NULL,
    text          TEXT NOT NULL
);

CREATE INDEX idx_inv_ev_hypothesis ON investigation_evidence(hypothesis_id);

CREATE TABLE investigation_action (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    investigation_id  BIGINT NOT NULL REFERENCES investigation(id) ON DELETE CASCADE,
    text              TEXT   NOT NULL,
    decision          VARCHAR(20),
    decided_by        BIGINT REFERENCES app_user(id),
    decided_at        TIMESTAMPTZ,
    CONSTRAINT investigation_action_decision_check CHECK (decision IS NULL OR decision IN ('ACCEPTED', 'REJECTED'))
);

CREATE INDEX idx_inv_action_investigation ON investigation_action(investigation_id);
