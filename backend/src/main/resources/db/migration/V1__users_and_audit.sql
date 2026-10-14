-- Users and the audit log. The table is app_user because "user" is reserved in PostgreSQL.

CREATE TABLE app_user (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      varchar(50)  NOT NULL UNIQUE,
    display_name  varchar(100) NOT NULL,
    password_hash varchar(100) NOT NULL,
    role          varchar(20)  NOT NULL CHECK (role IN ('ADMIN', 'ENGINEER', 'VIEWER')),
    enabled       boolean      NOT NULL DEFAULT true,
    token_version integer      NOT NULL DEFAULT 0,
    created_at    timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE audit_entry (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id    bigint      NOT NULL REFERENCES app_user (id),
    action      varchar(50) NOT NULL,
    item_type   varchar(50) NOT NULL,
    item_id     bigint,
    details     text,
    recorded_at timestamptz NOT NULL
);

-- Audit (and later timeline) entries are append-only: correct one by adding another.
CREATE FUNCTION reject_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER audit_entry_append_only
    BEFORE UPDATE OR DELETE ON audit_entry
    FOR EACH ROW EXECUTE FUNCTION reject_change();
