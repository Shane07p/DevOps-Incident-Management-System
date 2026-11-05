-- Email is the sign-in name, and the role and enabled flag live on the membership (D48).
DROP INDEX app_user_username_key;
ALTER TABLE app_user DROP COLUMN username;
ALTER TABLE app_user DROP CONSTRAINT app_user_role_check;
ALTER TABLE app_user DROP COLUMN role;
ALTER TABLE app_user DROP COLUMN enabled;
-- Google-only accounts (part 2) have no password.
ALTER TABLE app_user ALTER COLUMN password_hash DROP NOT NULL;
