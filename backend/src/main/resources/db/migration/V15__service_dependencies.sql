-- Service dependencies for the service map and blast radius (FR-14.2, #154), entered by hand in
-- Sprint 4. One row per link: service_id depends on depends_on_id. Each link belongs to an org (D48).

CREATE TABLE service_dependency (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id        bigint NOT NULL REFERENCES organization (id),
    service_id    bigint NOT NULL REFERENCES service (id) ON DELETE CASCADE,
    depends_on_id bigint NOT NULL REFERENCES service (id) ON DELETE CASCADE,
    CONSTRAINT service_dependency_link_key UNIQUE (service_id, depends_on_id),
    CONSTRAINT service_dependency_no_self_dep CHECK (service_id <> depends_on_id)
);

CREATE INDEX service_dependency_org_idx ON service_dependency (org_id);
CREATE INDEX service_dependency_depends_on_idx ON service_dependency (depends_on_id);

-- A service's health on the map comes from its monitors (#73); a monitor never checked says nothing yet.
ALTER TABLE uptime_monitor ADD COLUMN last_checked_at timestamptz;
