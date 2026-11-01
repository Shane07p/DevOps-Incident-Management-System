-- Service dependencies and blast radius (Issue #154).

CREATE TABLE service_dependency (
    service_id    bigint NOT NULL REFERENCES service (id) ON DELETE CASCADE,
    depends_on_id bigint NOT NULL REFERENCES service (id) ON DELETE CASCADE,
    PRIMARY KEY (service_id, depends_on_id),
    CONSTRAINT service_dependency_no_self_dep CHECK (service_id <> depends_on_id)
);

CREATE INDEX service_dependency_depends_on_idx ON service_dependency (depends_on_id);
