-- In-app notifications (FR-07.1, FR-07.8, #70).

CREATE TABLE notification (
    id            bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recipient_id  bigint        NOT NULL REFERENCES app_user (id),
    incident_id   bigint        REFERENCES incident (id),
    message       varchar(500)  NOT NULL,
    created_at    timestamptz   NOT NULL,
    read_at       timestamptz,
    CONSTRAINT notification_message_not_blank CHECK (length(trim(message)) > 0)
);

CREATE INDEX notification_recipient_idx ON notification (recipient_id, created_at DESC, id DESC);
CREATE INDEX notification_recipient_unread_idx ON notification (recipient_id) WHERE read_at IS NULL;
