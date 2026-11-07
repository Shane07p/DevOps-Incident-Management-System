-- Personal access tokens for AI assistants through MCP (D38, FR-23, #151).
-- Each token belongs to a user and their org (D48). Only the SHA-256 hash is stored.

CREATE TABLE personal_access_token (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id       bigint       NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    user_id      bigint       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name         varchar(100) NOT NULL,
    token_hash   varchar(64)  NOT NULL,
    created_at   timestamptz  NOT NULL,
    expires_at   timestamptz  NOT NULL,
    last_used_at timestamptz,
    revoked_at   timestamptz
);

-- Token lookup across all orgs is by SHA-256 hash.
CREATE UNIQUE INDEX pat_token_hash_idx ON personal_access_token (token_hash);
CREATE UNIQUE INDEX uq_personal_access_token_user_name ON personal_access_token (user_id, lower(name));
CREATE INDEX pat_user_idx ON personal_access_token (user_id);
CREATE INDEX pat_org_idx ON personal_access_token (org_id);
CREATE INDEX pat_expires_at_idx ON personal_access_token (expires_at);

