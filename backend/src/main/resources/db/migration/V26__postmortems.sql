-- One draft/reviewed postmortem per incident (FR-11, D17, #239). V25 belongs to #84.
CREATE TABLE postmortem (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id      bigint NOT NULL REFERENCES organization (id),
    incident_id bigint NOT NULL UNIQUE REFERENCES incident (id) ON DELETE CASCADE,
    status      varchar(20) NOT NULL CHECK (status IN ('DRAFT', 'REVIEWED')),
    summary     text CHECK (char_length(summary) <= 10000),
    impact      text CHECK (char_length(impact) <= 10000),
    root_cause  text CHECK (char_length(root_cause) <= 10000),
    resolution  text CHECK (char_length(resolution) <= 10000),
    went_well   text CHECK (char_length(went_well) <= 10000),
    went_badly  text CHECK (char_length(went_badly) <= 10000),
    author_id   bigint NOT NULL REFERENCES app_user (id),
    reviewed_by bigint REFERENCES app_user (id),
    reviewed_at timestamptz,
    updated_at  timestamptz NOT NULL,
    version     bigint NOT NULL DEFAULT 0,
    CONSTRAINT postmortem_review_check CHECK (
        (status = 'DRAFT' AND reviewed_by IS NULL AND reviewed_at IS NULL)
        OR (status = 'REVIEWED' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
            AND reviewed_by <> author_id)
    )
);

CREATE INDEX postmortem_org_idx ON postmortem (org_id);
