-- Notification kind (#243, #278): distinguishes genuine pages (incident declaration on-call
-- assignment and escalations) from informational assignments (reassignment, role assignment).

ALTER TABLE notification
    ADD COLUMN kind varchar(30);

-- Backfill existing notifications:

-- 1. Notifications without an incident are SYSTEM
UPDATE notification
SET kind = 'SYSTEM'
WHERE incident_id IS NULL;

-- 2. Role assignments explicitly mention role assignment in their message:
-- "You have been assigned the role COMMS on incident INC-1: ..."
UPDATE notification
SET kind = 'ROLE_ASSIGNMENT'
WHERE kind IS NULL
  AND message LIKE 'You have been assigned the role %';

-- 3. Escalations explicitly mention escalation in their message:
-- "Incident ... was escalated to you (level ...): ..." or
-- "Incident ... was escalated to you as the fallback contact: ..."
UPDATE notification
SET kind = 'PAGE'
WHERE kind IS NULL
  AND message LIKE '%was escalated to you%';

-- 4. Assignment notifications (initial on-call page vs reassignment):
-- Deterministic 1-to-1 matching: each eligible assignment notification matches at most one
-- timeline event within 5 seconds, and each timeline event matches at most one notification.
-- When multiple notifications prefer the same event, the closest pair matches first, and
-- subsequent notifications remain eligible to match other distinct available events.
WITH RECURSIVE
ordered_candidates AS (
    SELECT
        ROW_NUMBER() OVER (
            ORDER BY abs(extract(epoch from (n.created_at - t.recorded_at))) ASC,
                     n.created_at ASC,
                     t.recorded_at ASC,
                     n.id ASC,
                     t.id ASC
        ) AS row_num,
        n.id AS notification_id,
        t.id AS timeline_id,
        CASE
            WHEN t.event_type = 'ASSIGNED' THEN 'PAGE'
            ELSE 'REASSIGNMENT'
        END AS matched_kind
    FROM notification n
    JOIN timeline_entry t ON t.incident_id = n.incident_id
    JOIN app_user u ON u.id = n.recipient_id
    WHERE n.kind IS NULL
      AND n.incident_id IS NOT NULL
      AND n.message LIKE 'You have been assigned to incident %'
      AND (
          (t.event_type = 'ASSIGNED' AND t.actor_id IS NULL AND t.details = 'Assigned to ' || u.display_name)
          OR
          (t.event_type = 'REASSIGNED' AND t.details LIKE 'Reassigned from % to ' || u.display_name)
      )
      AND abs(extract(epoch from (n.created_at - t.recorded_at))) <= 5
),
matches AS (
    SELECT
        row_num,
        notification_id,
        timeline_id,
        matched_kind,
        ARRAY[notification_id] AS used_notifications,
        ARRAY[timeline_id] AS used_timelines,
        true AS is_accepted
    FROM ordered_candidates
    WHERE row_num = 1

    UNION ALL

    SELECT
        c.row_num,
        c.notification_id,
        c.timeline_id,
        c.matched_kind,
        CASE
            WHEN NOT (c.notification_id = ANY(m.used_notifications) OR c.timeline_id = ANY(m.used_timelines))
            THEN array_append(m.used_notifications, c.notification_id)
            ELSE m.used_notifications
        END,
        CASE
            WHEN NOT (c.notification_id = ANY(m.used_notifications) OR c.timeline_id = ANY(m.used_timelines))
            THEN array_append(m.used_timelines, c.timeline_id)
            ELSE m.used_timelines
        END,
        NOT (c.notification_id = ANY(m.used_notifications) OR c.timeline_id = ANY(m.used_timelines))
    FROM matches m
    JOIN ordered_candidates c ON c.row_num = m.row_num + 1
)
UPDATE notification
SET kind = m.matched_kind
FROM matches m
WHERE notification.id = m.notification_id
  AND m.is_accepted = true;

-- 5. Any ambiguous or unclassified notifications safely default to SYSTEM rather than PAGE:
UPDATE notification
SET kind = 'SYSTEM'
WHERE kind IS NULL;

-- Enforce NOT NULL, DEFAULT 'SYSTEM', and CHECK constraint:
ALTER TABLE notification
    ALTER COLUMN kind SET NOT NULL,
    ALTER COLUMN kind SET DEFAULT 'SYSTEM',
    ADD CONSTRAINT notification_kind_check CHECK (kind IN ('PAGE', 'REASSIGNMENT', 'ROLE_ASSIGNMENT', 'SYSTEM'));

CREATE INDEX notification_org_kind_created_idx ON notification (org_id, kind, created_at);
