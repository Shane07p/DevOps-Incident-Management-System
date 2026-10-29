-- Alert intake, deduplication, and source-scoped correlation grouping (FR-03, US-04).

ALTER TABLE incident
    ADD COLUMN correlation_source varchar(100),
    ADD COLUMN correlation_key varchar(200);

CREATE INDEX incident_alert_correlation_idx
    ON incident (service_id, correlation_source, correlation_key, status);

CREATE TABLE alert (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source              varchar(100) NOT NULL,
    event_id            varchar(200) NOT NULL,
    service_id          bigint       NOT NULL REFERENCES service (id),
    correlation_key     varchar(200),
    incident_id         bigint       NOT NULL REFERENCES incident (id),
    title               varchar(200) NOT NULL,
    description         text         NOT NULL,
    severity            varchar(10)  NOT NULL,
    payload             text         NOT NULL,
    payload_fingerprint varchar(64)  NOT NULL,
    status              varchar(20)  NOT NULL,
    received_at         timestamptz  NOT NULL,
    created_at          timestamptz  NOT NULL,
    CONSTRAINT alert_severity_check CHECK (severity IN ('SEV1', 'SEV2', 'SEV3', 'SEV4')),
    CONSTRAINT alert_status_check CHECK (status IN ('NEW', 'ATTACHED', 'ROUTED', 'DUPLICATE', 'IGNORED')),
    CONSTRAINT alert_source_event_id_key UNIQUE (source, event_id)
);

CREATE INDEX alert_service_idx ON alert (service_id);
CREATE INDEX alert_incident_idx ON alert (incident_id);
CREATE INDEX alert_created_idx ON alert (created_at DESC, id DESC);
