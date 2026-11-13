-- Follow-ups (#240, FR-11.3, US-14): action items from an incident, each with an owner, a due date, and a
-- status. They may stay open after the incident closes (D17). Overdue is computed, never stored: the due date
-- has passed (UTC) and the status is not DONE.

CREATE TABLE follow_up (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id       bigint       NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    incident_id  bigint       NOT NULL REFERENCES incident (id) ON DELETE CASCADE,
    title        varchar(200) NOT NULL CHECK (btrim(title) <> ''),
    kind         varchar(20)  NOT NULL
        CONSTRAINT follow_up_kind_check CHECK (kind IN ('CORRECTIVE', 'PREVENTIVE')),
    owner_id     bigint       NOT NULL REFERENCES app_user (id),
    due_date     date         NOT NULL,
    status       varchar(20)  NOT NULL
        CONSTRAINT follow_up_status_check CHECK (status IN ('OPEN', 'IN_PROGRESS', 'DONE')),
    completed_at timestamptz,
    created_at   timestamptz  NOT NULL,
    -- Done, and only done, carries the time it was done.
    CONSTRAINT follow_up_completed_check CHECK ((status = 'DONE') = (completed_at IS NOT NULL))
);

CREATE INDEX follow_up_incident_idx ON follow_up (incident_id, created_at, id);
-- "My follow-ups" and Insights read across incidents by owner, status, and due date.
CREATE INDEX follow_up_org_owner_idx ON follow_up (org_id, owner_id, status, due_date);
CREATE INDEX follow_up_org_due_idx ON follow_up (org_id, due_date);
