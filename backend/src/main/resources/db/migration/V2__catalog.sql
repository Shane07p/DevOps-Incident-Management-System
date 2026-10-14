-- Service catalog (US-05): each service has an owner, a criticality, and SLA targets per severity.

CREATE TABLE service (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        varchar(100)  NOT NULL,
    description varchar(1000),
    owner_id    bigint        NOT NULL REFERENCES app_user (id),
    criticality varchar(20)   NOT NULL CHECK (criticality IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    created_at  timestamptz   NOT NULL,
    updated_at  timestamptz   NOT NULL
);

-- "Payments" and "payments" are the same service.
CREATE UNIQUE INDEX service_name_unique ON service (lower(name));

CREATE TABLE service_sla_target (
    service_id         bigint     NOT NULL REFERENCES service (id) ON DELETE CASCADE,
    severity           varchar(4) NOT NULL CHECK (severity IN ('SEV1', 'SEV2', 'SEV3', 'SEV4')),
    response_minutes   integer    NOT NULL CHECK (response_minutes > 0),
    resolution_minutes integer    NOT NULL CHECK (resolution_minutes > 0),
    PRIMARY KEY (service_id, severity),
    CHECK (resolution_minutes >= response_minutes)
);
