-- Incident acknowledgement (FR-06, FR-07.4, FR-16.2, US-08).
-- Stores when the first accepted acknowledgement occurred, stopping the response clock.

ALTER TABLE incident ADD COLUMN acknowledged_at timestamptz;
