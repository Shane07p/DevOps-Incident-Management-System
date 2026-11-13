-- When RESOLVED stopped the resolution clock (FR-16.2, #84). Set once; null while the clock runs.

ALTER TABLE incident ADD COLUMN resolved_at timestamptz;
