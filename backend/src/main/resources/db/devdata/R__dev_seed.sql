-- Local and test data only; production sets FLYWAY_LOCATIONS=classpath:db/migration.
-- Every user's password is DevPassw0rd!

INSERT INTO app_user (username, display_name, password_hash, role)
VALUES ('admin',    'Dev Admin',    '{bcrypt}$2a$10$VIfFAxI6UFgBeWMdFEvAp.16koHo8wb8LRuqky0e47iWLefM5Huwm', 'ADMIN'),
       ('engineer', 'Dev Engineer', '{bcrypt}$2a$10$VIfFAxI6UFgBeWMdFEvAp.16koHo8wb8LRuqky0e47iWLefM5Huwm', 'ENGINEER'),
       ('viewer',   'Dev Viewer',   '{bcrypt}$2a$10$VIfFAxI6UFgBeWMdFEvAp.16koHo8wb8LRuqky0e47iWLefM5Huwm', 'VIEWER')
ON CONFLICT (username) DO NOTHING;

INSERT INTO service (name, description, owner_id, criticality, created_at, updated_at)
SELECT 'Payments', 'Card and wallet payments', id, 'CRITICAL', now(), now()
FROM app_user
WHERE username = 'admin'
ON CONFLICT DO NOTHING;

INSERT INTO service_sla_target (service_id, severity, response_minutes, resolution_minutes)
SELECT s.id, t.severity, t.response_minutes, t.resolution_minutes
FROM service s,
     (VALUES ('SEV1', 15, 240), ('SEV2', 60, 480), ('SEV3', 240, 2880), ('SEV4', 1440, 10080))
         AS t (severity, response_minutes, resolution_minutes)
WHERE lower(s.name) = 'payments'
ON CONFLICT DO NOTHING;
