-- The public status page (#160, D45, FR-21): an Admin picks which services appear and under
-- what public name, and customer-facing updates are kept apart from internal notes (FR-21.1).

ALTER TABLE service
    ADD COLUMN public_visible boolean NOT NULL DEFAULT false,
    ADD COLUMN public_name    varchar(100),
    ADD CONSTRAINT service_public_name_check
        CHECK (NOT public_visible OR (public_name IS NOT NULL AND btrim(public_name) <> ''));

-- One row per published update. Never edited or deleted through the application.
CREATE TABLE public_update (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id      bigint      NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    incident_id bigint      NOT NULL REFERENCES incident (id) ON DELETE CASCADE,
    status      varchar(20) NOT NULL
        CONSTRAINT public_update_status_check
            CHECK (status IN ('INVESTIGATING', 'IDENTIFIED', 'MONITORING', 'RESOLVED')),
    message     text        NOT NULL CHECK (btrim(message) <> ''),
    author_id   bigint      NOT NULL REFERENCES app_user (id),
    created_at  timestamptz NOT NULL
);

CREATE INDEX public_update_org_idx ON public_update (org_id);
CREATE INDEX public_update_incident_idx ON public_update (incident_id, created_at, id);
