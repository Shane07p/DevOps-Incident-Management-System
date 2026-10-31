-- Uptime monitor configuration and check history.

CREATE TABLE uptime_monitor (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name                varchar(100) NOT NULL,
    url                 varchar(2048) NOT NULL,
    expected_status     integer NOT NULL DEFAULT 200,
    interval_seconds    integer NOT NULL DEFAULT 60,
    service_id          bigint NOT NULL REFERENCES service(id),
    failure_threshold   integer NOT NULL DEFAULT 3,
    enabled             boolean NOT NULL DEFAULT true,
    consecutive_failures integer NOT NULL DEFAULT 0,
    outage_started_at   timestamptz,
    active_alert_id     bigint REFERENCES alert(id),
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    CONSTRAINT uptime_monitor_expected_status_check
        CHECK (expected_status BETWEEN 100 AND 599),
    CONSTRAINT uptime_monitor_interval_check
        CHECK (interval_seconds > 0),
    CONSTRAINT uptime_monitor_failure_threshold_check
        CHECK (failure_threshold > 0),
    CONSTRAINT uptime_monitor_consecutive_failures_check
        CHECK (consecutive_failures >= 0)
);

CREATE INDEX uptime_monitor_enabled_idx
    ON uptime_monitor(enabled);

CREATE TABLE monitor_check_result (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    monitor_id      bigint NOT NULL REFERENCES uptime_monitor(id),
    checked_at      timestamptz NOT NULL,
    success         boolean NOT NULL,
    actual_status   integer,
    response_time_ms bigint,
    error_message   text
);

CREATE INDEX monitor_check_result_history_idx
    ON monitor_check_result(monitor_id, checked_at DESC, id DESC);
