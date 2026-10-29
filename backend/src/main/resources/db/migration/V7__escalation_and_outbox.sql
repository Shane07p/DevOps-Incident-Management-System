-- Escalation policies, steps, tasks, and outbox for RabbitMQ (Sprint 3).

CREATE TABLE escalation_policy (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    service_id  bigint NOT NULL UNIQUE REFERENCES service (id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL
);

CREATE TABLE escalation_step (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_id     bigint NOT NULL REFERENCES escalation_policy (id) ON DELETE CASCADE,
    step_number   integer NOT NULL,
    responder_id  bigint NOT NULL REFERENCES app_user (id),
    wait_minutes  integer NOT NULL,
    CONSTRAINT escalation_step_number_check CHECK (step_number > 0),
    CONSTRAINT escalation_step_wait_minutes_check CHECK (wait_minutes > 0),
    CONSTRAINT escalation_step_policy_number_key UNIQUE (policy_id, step_number)
);

CREATE INDEX escalation_step_policy_idx ON escalation_step (policy_id, step_number);
CREATE INDEX escalation_step_responder_idx ON escalation_step (responder_id);

CREATE TABLE escalation_task (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id       bigint NOT NULL UNIQUE REFERENCES incident (id) ON DELETE CASCADE,
    current_step      integer NOT NULL DEFAULT 0,
    next_due_at       timestamptz,
    state             varchar(20) NOT NULL,
    fallback_notified boolean NOT NULL DEFAULT false,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    CONSTRAINT escalation_task_state_check CHECK (state IN ('PENDING', 'CANCELLED', 'EXHAUSTED'))
);

CREATE INDEX escalation_task_due_idx ON escalation_task (state, next_due_at);

CREATE TABLE escalation_task_step (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id       bigint NOT NULL REFERENCES escalation_task (id) ON DELETE CASCADE,
    step_number   integer NOT NULL,
    responder_id  bigint NOT NULL REFERENCES app_user (id),
    wait_minutes  integer NOT NULL,
    CONSTRAINT escalation_task_step_number_check CHECK (step_number > 0),
    CONSTRAINT escalation_task_step_wait_minutes_check CHECK (wait_minutes > 0),
    CONSTRAINT escalation_task_step_task_number_key UNIQUE (task_id, step_number)
);

CREATE INDEX escalation_task_step_task_idx ON escalation_task_step (task_id, step_number);
CREATE INDEX escalation_task_step_responder_idx ON escalation_task_step (responder_id);

CREATE TABLE outbox (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type  varchar(50) NOT NULL,
    aggregate_id    bigint NOT NULL,
    event_type      varchar(50) NOT NULL,
    payload         text NOT NULL,
    status          varchar(20) NOT NULL DEFAULT 'PENDING',
    created_at      timestamptz NOT NULL,
    processed_at    timestamptz,
    CONSTRAINT outbox_status_check CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

CREATE INDEX outbox_status_created_idx ON outbox (status, created_at);
