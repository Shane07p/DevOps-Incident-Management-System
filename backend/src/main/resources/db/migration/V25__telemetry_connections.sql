-- Each organization connects its own Grafana (D49, #260). Telemetry is read only through the org's
-- own connection: there is no server-wide stack, so one org can never see another org's logs or metrics.

CREATE TABLE telemetry_connection (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id           bigint       NOT NULL REFERENCES organization (id),
    -- Either store may be left out: an org can connect only logs, or only metrics.
    prometheus_url   varchar(500),
    prometheus_user  varchar(100),
    loki_url         varchar(500),
    loki_user        varchar(100),
    -- The read-only token, encrypted with AES-GCM under TELEMETRY_SECRET_KEY. Never returned.
    token_ciphertext text         NOT NULL,
    -- How the org's own telemetry is named; the defaults follow common Prometheus conventions.
    service_label    varchar(100) NOT NULL,
    requests_metric  varchar(200) NOT NULL,
    status_label     varchar(100) NOT NULL,
    duration_metric  varchar(200) NOT NULL,
    updated_by       bigint       NOT NULL REFERENCES app_user (id),
    updated_at       timestamptz  NOT NULL,
    version          bigint       NOT NULL DEFAULT 0,
    CONSTRAINT telemetry_connection_org_key UNIQUE (org_id),
    CONSTRAINT telemetry_connection_store_check CHECK (prometheus_url IS NOT NULL OR loki_url IS NOT NULL)
);

-- The value of the org's service label for this service. Null means the default: the name in lower
-- case, words joined by hyphens.
ALTER TABLE service ADD COLUMN telemetry_label varchar(100);
