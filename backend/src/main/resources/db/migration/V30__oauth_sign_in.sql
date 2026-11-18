-- Sign in with Google and GitHub (D50). Neither table holds an org's data: a person and their provider
-- accounts exist before, and apart from, any org.

-- One row per provider account linked to a person. The provider's own id (Google's "sub", GitHub's numeric
-- id) never changes, so it, not the email, finds the person on later sign-ins.
CREATE TABLE user_identity (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    bigint       NOT NULL REFERENCES app_user (id),
    provider   varchar(20)  NOT NULL,
    subject    varchar(255) NOT NULL,
    email      varchar(254) NOT NULL,
    created_at timestamptz  NOT NULL,
    CONSTRAINT user_identity_provider_check CHECK (provider IN ('GOOGLE', 'GITHUB')),
    CONSTRAINT user_identity_provider_subject_key UNIQUE (provider, subject)
);

CREATE INDEX user_identity_user_id_idx ON user_identity (user_id);

-- A finished provider sign-in, handed to the browser as a one-time code in the URL fragment. LOGIN codes
-- name an existing person and live 2 minutes; SIGNUP codes hold a new person's details until they name
-- their org, and live 30 minutes. Only the SHA-256 of a code is stored, and a code works once.
CREATE TABLE oauth_ticket (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code_hash    varchar(64)  NOT NULL,
    kind         varchar(10)  NOT NULL,
    user_id      bigint       REFERENCES app_user (id),
    provider     varchar(20)  NOT NULL,
    subject      varchar(255) NOT NULL,
    email        varchar(254) NOT NULL,
    display_name varchar(100) NOT NULL,
    created_at   timestamptz  NOT NULL,
    expires_at   timestamptz  NOT NULL,
    used_at      timestamptz,
    CONSTRAINT oauth_ticket_code_hash_key UNIQUE (code_hash),
    CONSTRAINT oauth_ticket_kind_check CHECK (kind IN ('LOGIN', 'SIGNUP')),
    CONSTRAINT oauth_ticket_provider_check CHECK (provider IN ('GOOGLE', 'GITHUB')),
    CONSTRAINT oauth_ticket_login_has_user_check CHECK (kind <> 'LOGIN' OR user_id IS NOT NULL)
);
