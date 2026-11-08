-- Fix personal access token name uniqueness: scope to org and active (non-revoked) tokens only (Issue #151, PR #219).
DROP INDEX IF EXISTS uq_personal_access_token_user_name;

CREATE UNIQUE INDEX uq_personal_access_token_user_name
    ON personal_access_token (org_id, user_id, lower(name))
    WHERE revoked_at IS NULL;
