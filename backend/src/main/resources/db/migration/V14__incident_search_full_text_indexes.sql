CREATE INDEX incident_title_fts_idx
    ON incident USING gin (to_tsvector('english', coalesce(title, '')));

CREATE INDEX incident_description_fts_idx
    ON incident USING gin (to_tsvector('english', coalesce(description, '')));

CREATE INDEX timeline_entry_details_fts_idx
    ON timeline_entry USING gin (to_tsvector('english', coalesce(details, '')));
