-- One draft/reviewed postmortem per incident (FR-11, D17, #239).
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

-- DR-06.3 judges closure by the highest severity an incident reached, so a downgrade cannot lift the rule.
-- Existing incidents start at their current severity, raised to any severity a SEVERITY_CHANGED entry moved
-- them away from ("Severity changed from SEV1 to SEV3: ..."). SEV1 sorts first, so MIN is the most severe.
ALTER TABLE incident ADD COLUMN highest_severity varchar(10);

UPDATE incident SET highest_severity = severity;

UPDATE incident i
SET highest_severity = LEAST(i.severity, earlier.most_severe)
FROM (
    SELECT incident_id, MIN(substring(details FROM '^Severity changed from (SEV[1-4]) ')) AS most_severe
    FROM timeline_entry
    WHERE event_type = 'SEVERITY_CHANGED'
    GROUP BY incident_id
) earlier
WHERE earlier.incident_id = i.id
  AND earlier.most_severe IS NOT NULL;

ALTER TABLE incident
    ALTER COLUMN highest_severity SET NOT NULL,
    ADD CONSTRAINT incident_severity_highest_check CHECK (highest_severity IN ('SEV1', 'SEV2', 'SEV3', 'SEV4'));
