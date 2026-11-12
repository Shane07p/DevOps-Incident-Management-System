-- Heartbeats (FR-22, D29): a ping URL per scheduled job. A late or missing ping raises an alert;
-- a later ping records recovery. Both tables belong to an org (D48).

CREATE TABLE heartbeat (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id            bigint        NOT NULL REFERENCES organization (id),
    service_id        bigint        NOT NULL REFERENCES service (id),
    name              varchar(100)  NOT NULL,
    interval_seconds  integer       NOT NULL,
    grace_seconds     integer       NOT NULL,
    token             varchar(64)   NOT NULL UNIQUE,
    enabled           boolean       NOT NULL,
    next_due_at       timestamptz   NOT NULL,
    late_after        timestamptz   NOT NULL,
    last_ping_at      timestamptz,
    outage_started_at timestamptz,
    active_alert_id   bigint        REFERENCES alert (id),
    version           bigint        NOT NULL DEFAULT 0,
    created_at        timestamptz   NOT NULL,
    updated_at        timestamptz   NOT NULL,
    CONSTRAINT heartbeat_interval_check CHECK (interval_seconds BETWEEN 30 AND 2592000),
    CONSTRAINT heartbeat_grace_check CHECK (grace_seconds BETWEEN 0 AND 86400)
);

CREATE INDEX heartbeat_org_idx ON heartbeat (org_id);
CREATE INDEX heartbeat_service_idx ON heartbeat (service_id);
CREATE INDEX heartbeat_token_idx ON heartbeat (token);
CREATE INDEX heartbeat_due_idx ON heartbeat (late_after) WHERE enabled AND active_alert_id IS NULL;
