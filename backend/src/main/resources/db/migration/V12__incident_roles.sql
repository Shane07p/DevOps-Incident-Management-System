-- Incident roles: Lead, Comms, Ops (D39, FR-05.4).
-- Scribe is dropped: the timeline records events by itself.
-- Role assignments are independent of the incident's assignee (FR-05.4).

ALTER TABLE incident ADD COLUMN lead_id bigint REFERENCES app_user (id);
ALTER TABLE incident ADD COLUMN comms_id bigint REFERENCES app_user (id);
ALTER TABLE incident ADD COLUMN ops_id bigint REFERENCES app_user (id);

CREATE INDEX incident_lead_idx ON incident (lead_id);
CREATE INDEX incident_comms_idx ON incident (comms_id);
CREATE INDEX incident_ops_idx ON incident (ops_id);
