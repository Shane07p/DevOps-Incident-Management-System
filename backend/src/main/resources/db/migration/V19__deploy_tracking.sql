-- Deployment tracking for releases and incident correlation (FR-20, US-19, #152).
-- Each deployment belongs to a service and its org (D48).

CREATE TABLE deployment (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id       bigint       NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    service_id   bigint       NOT NULL REFERENCES service (id) ON DELETE CASCADE,
    version      varchar(100) NOT NULL,
    commit_hash  varchar(100) NOT NULL,
    author       varchar(100) NOT NULL,
    deployed_at  timestamptz  NOT NULL,
    created_at   timestamptz  NOT NULL
);

-- Deduplication: a repeat of the same service, version, and deployed_at in an org is ignored.
CREATE UNIQUE INDEX uq_deployment_service_version_time
    ON deployment (org_id, service_id, version, deployed_at);

CREATE INDEX idx_deployment_service_deployed_at
    ON deployment (service_id, deployed_at DESC, id DESC);

CREATE INDEX idx_deployment_org_id
    ON deployment (org_id);
