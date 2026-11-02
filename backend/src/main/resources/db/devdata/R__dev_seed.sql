-- Development data only. Flyway loads this file only when FLYWAY_LOCATIONS includes
-- classpath:db/devdata (D27); production and the default test run never see it.
-- One org, "Dev org", with admin@settle.local, engineer@settle.local, and viewer@settle.local.
-- Every user's password is DevPassw0rd!
-- Repeatable and idempotent: rows that already exist are left as they are.

INSERT INTO organization (name, slug, created_at)
VALUES ('Dev org', 'dev-org', now())
ON CONFLICT (slug) DO NOTHING;

INSERT INTO app_user (username, email, display_name, password_hash, role, created_at)
VALUES ('admin',    'admin@settle.local',    'Dev Admin',    '{bcrypt}$2a$10$VIfFAxI6UFgBeWMdFEvAp.16koHo8wb8LRuqky0e47iWLefM5Huwm', 'ADMIN',    now()),
       ('engineer', 'engineer@settle.local', 'Dev Engineer', '{bcrypt}$2a$10$VIfFAxI6UFgBeWMdFEvAp.16koHo8wb8LRuqky0e47iWLefM5Huwm', 'ENGINEER', now()),
       ('viewer',   'viewer@settle.local',   'Dev Viewer',   '{bcrypt}$2a$10$VIfFAxI6UFgBeWMdFEvAp.16koHo8wb8LRuqky0e47iWLefM5Huwm', 'VIEWER',   now())
ON CONFLICT ((lower(username))) DO NOTHING;

INSERT INTO membership (org_id, user_id, role, created_at)
SELECT o.id, u.id, r.role, now()
FROM organization o
JOIN (VALUES ('admin@settle.local', 'ADMIN'), ('engineer@settle.local', 'ENGINEER'), ('viewer@settle.local', 'VIEWER'))
    AS r (email, role) ON true
JOIN app_user u ON lower(u.email) = r.email
WHERE o.slug = 'dev-org'
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO service (org_id, name, description, owner_id, criticality, created_at, updated_at)
SELECT o.id, 'Payments', 'Card and wallet payments', u.id, 'CRITICAL', now(), now()
FROM organization o JOIN app_user u ON lower(u.email) = 'admin@settle.local'
WHERE o.slug = 'dev-org'
ON CONFLICT (org_id, lower(name)) DO NOTHING;

INSERT INTO service_sla_target (service_id, severity, response_minutes, resolution_minutes)
SELECT s.id, t.severity, t.response_minutes, t.resolution_minutes
FROM service s
JOIN organization o ON o.id = s.org_id AND o.slug = 'dev-org'
CROSS JOIN (VALUES ('SEV1', 15, 240),
                   ('SEV2', 60, 480),
                   ('SEV3', 240, 2880),
                   ('SEV4', 1440, 10080)) AS t (severity, response_minutes, resolution_minutes)
WHERE s.name = 'Payments'
ON CONFLICT (service_id, severity) DO NOTHING;
