-- Runbooks: step-by-step fixes kept per service (FR-25, #155). Each runbook belongs to an org (D48).
-- runbook_step has no org_id: it is an element collection of runbook and is only read through it.

CREATE TABLE runbook (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id     bigint       NOT NULL REFERENCES organization (id),
    service_id bigint       NOT NULL REFERENCES service (id) ON DELETE CASCADE,
    title      varchar(200) NOT NULL,
    version    bigint       NOT NULL DEFAULT 0,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    CONSTRAINT runbook_title_check CHECK (btrim(title) <> '')
);

CREATE INDEX runbook_org_idx ON runbook (org_id);
CREATE INDEX runbook_service_idx ON runbook (service_id);

-- The steps in order: position 0 is the first.
CREATE TABLE runbook_step (
    runbook_id bigint        NOT NULL REFERENCES runbook (id) ON DELETE CASCADE,
    position   integer       NOT NULL,
    text       varchar(2000) NOT NULL,
    PRIMARY KEY (runbook_id, position),
    CONSTRAINT runbook_step_position_check CHECK (position >= 0)
);
