ALTER TABLE service
    ADD COLUMN fallback_contact_id bigint REFERENCES app_user(id);

CREATE TABLE on_call_shift (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    service_id    bigint NOT NULL REFERENCES service(id) ON DELETE CASCADE,
    responder_id  bigint NOT NULL REFERENCES app_user(id),
    start_time    timestamptz NOT NULL,
    end_time      timestamptz NOT NULL,
    CONSTRAINT on_call_shift_time_check CHECK (end_time > start_time)
);

CREATE INDEX idx_on_call_shift_service ON on_call_shift(service_id, start_time, end_time);
