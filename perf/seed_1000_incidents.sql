-- ============================================================================
-- Settle Incident Management System
-- Performance Testing Data Seed: 1,000 Incidents (Issue #250 / NFR-01.1)
-- ============================================================================
--
-- Purpose:
--   Populates exactly 1,000 valid, realistic incidents and associated timeline
--   entries for performance testing the Incident Board (GET /api/incidents)
--   and Incident Detail (GET /api/incidents/{id}) endpoints under NFR-01.1.
--
-- Schema & Integrity Details:
--   - Target Organization: 'Dev org' (slug: 'dev-org', id: 1 from R__dev_seed.sql)
--   - Target Service: 'Payments' (configured with SEV1-SEV4 SLA targets)
--   - Sequential Numbering: respects organization.next_incident_number and
--     the unique constraint on (org_id, number) from V10__organizations.sql
--   - Severity & SLA Targets: copied from service_sla_target per domain rules
--       * SEV1: response 15 min, resolution 240 min
--       * SEV2: response 60 min, resolution 480 min
--       * SEV3: response 240 min, resolution 2880 min
--       * SEV4: response 1440 min, resolution 10080 min
--   - Required Columns: highest_severity (V29__postmortems.sql), version, status, org_id
--   - Timeline Entries: an 'INCIDENT_CREATED' entry per incident (V3 & V10)
--   - Idempotency & Partial Seeding:
--       * If exactly 1,000 performance incidents exist, skips insertion cleanly.
--       * If a partial set exists (< 1,000), inserts only the missing count to reach 1,000.
--       * If > 1,000 exist, raises an exception to prevent accepting an invalid state.
--   - Timestamp Validity:
--       * created_at < acknowledged_at < resolved_at <= now() is strictly guaranteed.
--       * No timestamps are generated in the future.
--
-- Execution:
--   psql -U incident -d incident -f perf/seed_1000_incidents.sql
--   (or via Docker Compose: docker compose exec -T db psql -U incident -d incident < perf/seed_1000_incidents.sql)
-- ============================================================================

BEGIN;

DO $$
DECLARE
    v_org_id bigint;
    v_service_id bigint;
    v_admin_id bigint;
    v_start_number bigint;
    v_existing_perf_count integer;
    v_needed integer;
    v_now timestamptz := now();
BEGIN
    -- 1. Locate the Dev organization
    SELECT id INTO v_org_id
    FROM organization
    WHERE slug = 'dev-org';

    IF v_org_id IS NULL THEN
        RAISE EXCEPTION 'Dev organization (slug: dev-org) was not found. Please run baseline dev seed (R__dev_seed.sql) first.';
    END IF;

    -- 2. Locate the Payments service within the Dev organization
    SELECT id INTO v_service_id
    FROM service
    WHERE org_id = v_org_id AND lower(name) = 'payments';

    IF v_service_id IS NULL THEN
        RAISE EXCEPTION 'Payments service for Dev org was not found. Please run baseline dev seed (R__dev_seed.sql) first.';
    END IF;

    -- 3. Locate the Dev Admin user (used as incident reporter/assignee)
    SELECT id INTO v_admin_id
    FROM app_user
    WHERE lower(email) = 'admin@settle.local';

    IF v_admin_id IS NULL THEN
        RAISE EXCEPTION 'Dev Admin user (admin@settle.local) was not found. Please run baseline dev seed (R__dev_seed.sql) first.';
    END IF;

    -- 4. Check for existing performance-test incidents (idempotency guard)
    SELECT count(*) INTO v_existing_perf_count
    FROM incident
    WHERE org_id = v_org_id AND title LIKE 'Perf-Test Incident #%';

    -- If more than 1,000 exist, halt with an explicit exception
    IF v_existing_perf_count > 1000 THEN
        RAISE EXCEPTION 'Target organization % already has % performance test incidents, exceeding the expected 1,000. Manual review required.',
            v_org_id, v_existing_perf_count;
    END IF;

    -- If exactly 1,000 exist, skip insertion cleanly
    IF v_existing_perf_count = 1000 THEN
        RAISE NOTICE 'Seed check: Target organization % already has exactly 1,000 performance test incidents. Skipping insertion.', v_org_id;
        RETURN;
    END IF;

    -- Calculate the exact number of missing incidents needed to reach 1,000
    v_needed := 1000 - v_existing_perf_count;

    -- 5. Calculate safe start number avoiding collision with existing incidents
    SELECT COALESCE(MAX(number), 0) + 1 INTO v_start_number
    FROM incident
    WHERE org_id = v_org_id;

    SELECT GREATEST(v_start_number, next_incident_number) INTO v_start_number
    FROM organization
    WHERE id = v_org_id;

    -- 6. Insert exactly v_needed valid incidents with distributed severities and statuses
    WITH inserted_incidents AS (
        INSERT INTO incident (
            org_id,
            number,
            title,
            description,
            service_id,
            severity,
            highest_severity,
            status,
            reporter_id,
            assignee_id,
            created_at,
            acknowledged_at,
            resolved_at,
            response_target_minutes,
            resolution_target_minutes,
            version
        )
        SELECT
            v_org_id AS org_id,
            v_start_number + s.i AS number,
            'Perf-Test Incident #' || (v_start_number + s.i) AS title,
            'Automated synthetic incident record generated for k6 performance testing (NFR-01.1).' AS description,
            v_service_id AS service_id,
            -- Distribute severity evenly across SEV1, SEV2, SEV3, SEV4
            CASE ((v_existing_perf_count + s.i) % 4)
                WHEN 0 THEN 'SEV1'
                WHEN 1 THEN 'SEV2'
                WHEN 2 THEN 'SEV3'
                ELSE 'SEV4'
            END AS severity,
            -- highest_severity required by V29__postmortems.sql
            CASE ((v_existing_perf_count + s.i) % 4)
                WHEN 0 THEN 'SEV1'
                WHEN 1 THEN 'SEV2'
                WHEN 2 THEN 'SEV3'
                ELSE 'SEV4'
            END AS highest_severity,
            -- Distribute status: 0: OPEN, 1: ACKNOWLEDGED, 2: RESOLVED, 3: CLOSED
            CASE ((v_existing_perf_count + s.i) % 4)
                WHEN 0 THEN 'OPEN'
                WHEN 1 THEN 'ACKNOWLEDGED'
                WHEN 2 THEN 'RESOLVED'
                ELSE 'CLOSED'
            END AS status,
            v_admin_id AS reporter_id,
            -- Assignee is set for ACKNOWLEDGED, RESOLVED, and CLOSED incidents
            CASE WHEN ((v_existing_perf_count + s.i) % 4) != 0 THEN v_admin_id ELSE NULL END AS assignee_id,
            -- Creation timestamp: guaranteed in the past with a 2-hour buffer before now
            v_now - (((1000 - (v_existing_perf_count + s.i)) * interval '20 minutes') + interval '2 hours') AS created_at,
            -- Acknowledged timestamp: 10 minutes after creation (for ACKNOWLEDGED, RESOLVED, CLOSED)
            CASE 
                WHEN ((v_existing_perf_count + s.i) % 4) != 0 
                THEN (v_now - (((1000 - (v_existing_perf_count + s.i)) * interval '20 minutes') + interval '2 hours') + interval '10 minutes')
                ELSE NULL 
            END AS acknowledged_at,
            -- Resolved timestamp: 45 minutes after creation (for RESOLVED, CLOSED). Strictly in the past (<= v_now - 1h15m)
            CASE 
                WHEN ((v_existing_perf_count + s.i) % 4) IN (2, 3) 
                THEN (v_now - (((1000 - (v_existing_perf_count + s.i)) * interval '20 minutes') + interval '2 hours') + interval '45 minutes')
                ELSE NULL 
            END AS resolved_at,
            -- SLA response target minutes matching service SLA definitions
            CASE ((v_existing_perf_count + s.i) % 4)
                WHEN 0 THEN 15
                WHEN 1 THEN 60
                WHEN 2 THEN 240
                ELSE 1440
            END AS response_target_minutes,
            -- SLA resolution target minutes matching service SLA definitions
            CASE ((v_existing_perf_count + s.i) % 4)
                WHEN 0 THEN 240
                WHEN 1 THEN 480
                WHEN 2 THEN 2880
                ELSE 10080
            END AS resolution_target_minutes,
            0 AS version
        FROM generate_series(0, v_needed - 1) AS s(i)
        RETURNING id, org_id, number, created_at, severity
    )
    -- 7. Insert the corresponding initial timeline entry for each newly inserted incident
    INSERT INTO timeline_entry (
        org_id,
        incident_id,
        actor_id,
        event_type,
        details,
        recorded_at
    )
    SELECT
        ii.org_id,
        ii.id,
        v_admin_id,
        'INCIDENT_CREATED',
        'Reported as ' || ii.severity || ' on Payments',
        ii.created_at
    FROM inserted_incidents ii;

    -- 8. Advance the organization's next_incident_number sequence by the exact count added
    UPDATE organization
    SET next_incident_number = v_start_number + v_needed
    WHERE id = v_org_id;

    RAISE NOTICE 'Successfully seeded % performance test incidents (numbers % to %). Total count for org % is now 1,000.',
        v_needed, v_start_number, (v_start_number + v_needed - 1), v_org_id;
END $$;

COMMIT;
