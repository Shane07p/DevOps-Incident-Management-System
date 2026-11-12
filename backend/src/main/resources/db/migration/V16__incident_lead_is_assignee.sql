-- The Lead is the incident's assignee (D39), so it needs no column of its own.
-- V12 added lead_id as a separate holder; Comms and Ops keep theirs.

DROP INDEX IF EXISTS incident_lead_idx;
ALTER TABLE incident DROP COLUMN lead_id;
