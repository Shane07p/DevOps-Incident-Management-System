-- Uptime monitors (FR-22, D29): an HTTP check per service; after a number of failures in a row it
-- raises one alert through intake, and recovery updates that alert. Both tables belong to an org (D48).

CREATE TABLE uptime_monitor (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id               bigint        NOT NULL REFERENCES organization (id),
    service_id           bigint        NOT NULL REFERENCES service (id),
    name                 varchar(100)  NOT NULL,
    url                  varchar(2048) NOT NULL,
    expected_status      integer       NOT NULL,
    interval_seconds     integer       NOT NULL,
    failure_threshold    integer       NOT NULL,
    enabled              boolean       NOT NULL,
    -- Kept in the database, so a restart catches up on overdue checks.
    next_check_at        timestamptz   NOT NULL,
    consecutive_failures integer       NOT NULL DEFAULT 0,
    outage_started_at    timestamptz,
    -- The alert raised for the current outage, cleared on recovery.
    active_alert_id      bigint        REFERENCES alert (id),
    version              bigint        NOT NULL DEFAULT 0,
    created_at           timestamptz   NOT NULL,
    updated_at           timestamptz   NOT NULL,
    CONSTRAINT uptime_monitor_expected_status_check CHECK (expected_status BETWEEN 100 AND 599),
    CONSTRAINT uptime_monitor_interval_check CHECK (interval_seconds BETWEEN 30 AND 86400),
    CONSTRAINT uptime_monitor_failure_threshold_check CHECK (failure_threshold BETWEEN 1 AND 100),
    CONSTRAINT uptime_monitor_consecutive_failures_check CHECK (consecutive_failures >= 0)
);

CREATE INDEX uptime_monitor_org_idx ON uptime_monitor (org_id);
CREATE INDEX uptime_monitor_service_idx ON uptime_monitor (service_id);
CREATE INDEX uptime_monitor_due_idx ON uptime_monitor (next_check_at) WHERE enabled;

CREATE TABLE monitor_check_result (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id           bigint      NOT NULL REFERENCES organization (id),
    monitor_id       bigint      NOT NULL REFERENCES uptime_monitor (id),
    checked_at       timestamptz NOT NULL,
    success          boolean     NOT NULL,
    status_code      integer,
    response_time_ms bigint,
    error            text
);

CREATE INDEX monitor_check_result_org_idx ON monitor_check_result (org_id);
CREATE INDEX monitor_check_result_monitor_idx ON monitor_check_result (monitor_id, checked_at DESC, id DESC);

-- FR-22.2: recovery updates the alert it raised.
ALTER TABLE alert ADD COLUMN recovered_at timestamptz;
