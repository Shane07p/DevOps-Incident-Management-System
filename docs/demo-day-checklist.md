# Demo Day-Before Checklist

This document details the operational verification and readiness procedures for the Settle
(IT314 Group 2) live demonstration. It addresses the realities and limitations of running on cloud
free tiers (Supabase, Render, and Grafana Cloud) and provides a step-by-step checklist to guarantee a
seamless, zero-downtime evaluation.

## Free-Tier Constraints and Mitigations

Our production deployment runs entirely on free-tier infrastructure. Each provider imposes specific
inactivity constraints that can disrupt a live evaluation if not mitigated beforehand:

| Service | Free-Tier Constraint | Failure Mode During Demo | Mitigation Strategy |
| --- | --- | --- | --- |
| **Supabase** | Free PostgreSQL projects auto-pause after 7 days of inactivity. | Database connection refused (`500 Internal Server Error`). Restoring takes 1–3 minutes via the dashboard. | An external uptime monitor pings the backend health check, which queries the database, so the project never sits idle for 7 days. |
| **Render** | Free web instances spin down after 15 minutes of inactivity. | First HTTP request incurs a 50–90 second cold start delay, causing apparent frontend hanging or gateway timeouts. | The external uptime monitor keeps the backend awake. demo-warmup.sh confirms HTTP 200 before a demo. |
| **Grafana Cloud** | Prometheus metrics and Loki logs have a strict 14-day retention limit. | Incident investigation and telemetry dashboards show "No data" if no traffic has occurred recently. | Fresh telemetry comes from `demo-warmup.sh` (`POST /traffic/start`), which generates Demo Shop traffic spanning checkout, payments, and search. |
| **Vercel** | Edge functions and static assets may experience initial cache misses. | Minor initial asset loading delay on first browser page load. | Warm-up script probes root `/`; check the public status page manually below. |

The warm-up script requires backend `/actuator/health`, AI-service `/health`, and demo-shop `/health`
to return HTTP 200 after retries. A failed required check or a non-2xx `/traffic/start` response exits 1;
curl connection failures are recorded as HTTP 000. Frontend probing is informational and prints a warning
if it fails. Only a 2xx traffic response enables the `FRESH DATA INGESTED` banner; use the Grafana queries
below to verify the exported data itself.

The public `DEMO_SHOP_URL` is only the address used to call the control API. Traffic is generated
inside the demo shop, so `DEMO_SHOP_TRAFFIC_TARGET_URL` defaults to `http://127.0.0.1:8001`, which
the existing allowlist accepts at the default port. If the shop runs with another `PORT`, set the
optional environment variable `DEMO_SHOP_TRAFFIC_TARGET_URL` to that loopback port (for example
`http://127.0.0.1:10000` for `PORT=10000`). Keep the allowlist restricted; no wildcard or public-origin
exception is needed. `OTEL_METRIC_EXPORT_INTERVAL` is 60000 ms, matching the code default.

---

## Chronological Verification Procedure

Follow these steps sequentially to prepare the environment for evaluation.

### T-24 Hours: Day-Before Verification

1. **Verify Supabase Database Status:**
   - Log in to the [Supabase Dashboard](https://supabase.com/dashboard).
   - Check the `settle` project status. Ensure it displays **Active** (green indicator).
   - If paused, click **Restore project** and wait until migrations and connectivity resume.
   - Run a quick SQL verification query in the Supabase SQL editor:
     ```sql
     SELECT COUNT(*) FROM incident.service;
     ```
     Ensure the query returns the expected registered services without errors.

2. **Back Up the Database:**
   - Before the demo, and before loading demo data or running a migration, take a backup of the
     production schema (see [Database backup and restore](#database-backup-and-restore)):
     ```bash
     DB_URL=<Render's DB_URL> DB_USERNAME=<...> DB_PASSWORD=<...> scripts/db-backup.sh
     ```

3. **Run the Automated Warm-Up Script:**
   - From the repository root, run the warm-up script targeting production:
     ```bash
     BACKEND_URL=https://incident-backend-f340.onrender.com \
     AI_SERVICE_URL=${AI_SERVICE_URL:?Set the reachable AI-service URL} \
     FRONTEND_URL=https://dev-ops-incident-management-system.vercel.app \
     DEMO_SHOP_URL=${DEMO_SHOP_URL:-http://localhost:8001} \
     DEMO_SHOP_TRAFFIC_TARGET_URL=${DEMO_SHOP_TRAFFIC_TARGET_URL:-http://127.0.0.1:8001} \
     DEMO_SHOP_CONTROL_TOKEN=$DEMO_SHOP_CONTROL_TOKEN \
     ./scripts/demo-warmup.sh
     ```

4. **Inspect Render Deployment Logs:**
   - Check Render dashboards for `incident-backend-f340` (and demo shop service if hosted).
   - Confirm the Spring Boot application initialized without HikariCP pool exhaustion or Flyway validation errors:
     ```text
     HikariPool-1 - Added connection org.postgresql.jdbc.PgConnection
     Flyway Community Edition ... Schema "incident" is up to date. No migration necessary.
     Started IncidentApplication in ... seconds
     ```

4. **Verify Grafana Cloud Ingestion:**
   - Open your organization's Grafana Cloud instance or navigate to the Settle **Telemetry / Logs** tab.
   - Run a Loki query to confirm fresh log streams:
     ```logql
     {service="demo-shop"}
     ```
   - Run a Prometheus query to verify metric ingestion:
     ```promql
     sum(rate(demo_http_requests_total[5m]))
     ```
   - Verify that data timestamps are within the last 30 minutes.

5. **Verify User Accounts and Seed Data:**
   - Open the production frontend ([`https://dev-ops-incident-management-system.vercel.app`](https://dev-ops-incident-management-system.vercel.app)).
   - Sign in with the Admin, Engineer, and Viewer accounts created for this deployment. Production
     does not load the optional development seed accounts (D27); keep real credentials outside the repository.
   - Verify that the Service Catalog displays the registered services (e.g., `checkout`, `payments`, `search`).

---

### T-2 Hours: Morning-of-Demo Check

1. **Re-run the warm-up:**
   - Re-run `./scripts/demo-warmup.sh` to refresh Grafana metrics.
   - Ensure the backend responds with HTTP 200 on `/actuator/health` within 5 seconds (confirming the container is already hot).

2. **Verify Public Status Page:**
   - In a private/incognito window (unauthenticated), navigate to the public status page:
     `https://dev-ops-incident-management-system.vercel.app/status/{org-slug}`
   - Ensure the page renders company services, ongoing incidents, and uptime summaries without authentication prompts.

3. **Verify Fault Injection and Webhook Intake:**
   - Confirm Demo Shop fault switches respond properly (see [demo-shop-scenarios.md](2-design/demo-shop-scenarios.md)):
     ```bash
     curl -s -X POST "${DEMO_SHOP_URL:-http://localhost:8001}/faults/error_spike" \
       -H "Authorization: Bearer $DEMO_SHOP_CONTROL_TOKEN" \
       -H "Content-Type: application/json" \
       -d '{"enabled": true}'
     ```
   - Verify that an alert is ingested at `/webhooks/alerts` and groups into an incident.
   - Disable the fault switch after verifying:
     ```bash
     curl -s -X POST "${DEMO_SHOP_URL:-http://localhost:8001}/faults/error_spike" \
       -H "Authorization: Bearer $DEMO_SHOP_CONTROL_TOKEN" \
       -H "Content-Type: application/json" \
       -d '{"enabled": false}'
     ```

---

### T-15 Minutes: Pre-Presentation Readiness

1. **Execute Final Warmup Ping:**
   - Render free containers idle out after 15 minutes of inactivity. While the 12-hour cron keeps Supabase and Grafana awake, only a ping right before the demo eliminates the 50–90s Render cold start:
     ```bash
     curl -s https://incident-backend-f340.onrender.com/actuator/health
     ```
     (or run `./scripts/demo-warmup.sh` directly).

2. **Prepare Browser Presentation Tabs:**
   - Set up the demo tabs in order:
     - **Tab 1:** Settle Dashboard (`/incidents`) logged in as Engineer.
     - **Tab 2:** Public Status Page (`/status/{slug}`) in an Incognito window.
     - **Tab 3:** Demo Shop UI / Control Panel (`$DEMO_SHOP_URL` or `http://localhost:8001`).
     - **Tab 4:** Settle Incident Investigation / Postmortems view.
     - **Tab 5:** Grafana Cloud telemetry exploration tab (for evidence display).

---

## Emergency Fallback Plan (Fail-Safe Procedures)

If cloud provider infrastructure fails (e.g., unexpected Render outage, Supabase network partition, or venue WiFi restrictions), execute the local offline fallback plan:

### Fallback 1: Local Docker Compose Stack

All core services can run entirely locally without cloud dependencies:

1. Start PostgreSQL and dev seed data:
   ```bash
   docker compose up -d db
   ```
2. Start the backend, frontend, and demo shop containers:
   ```bash
   docker compose --profile app --profile ai --profile demo up -d
   ```
3. Verify local endpoints:
   - Frontend: `http://localhost:8081`
   - Backend API: `http://localhost:8080/actuator/health`
   - AI service: `http://localhost:8000/health`
   - Demo Shop: `http://localhost:8001/health`

### Fallback 2: Local AI Service and Mock Investigations

If Ollama or external AI inference is unavailable:
- The AI investigation endpoint returns deterministic fallback hypotheses without stalling incident progression.
- Recorded scenario fixtures (`ai-service/data/`) are available for offline evaluation.

---

## Readiness Checklist Matrix

| Check Item | Command / URL | Target Outcome | Verified By |
| --- | --- | --- | --- |
| Supabase Project Status | Supabase Dashboard | Status is "Active" | Team |
| Backend Health Probe | `GET /actuator/health` on `https://incident-backend-f340.onrender.com` | HTTP 200, status "UP" | Warmup Script |
| AI Service Health Probe | `GET /health` on `AI_SERVICE_URL` | HTTP 200 | Warmup Script |
| Database Connectivity | Verified via `/actuator/health` | `db` component UP | Warmup Script |
| Frontend Route Response | `GET /` on `https://dev-ops-incident-management-system.vercel.app` | HTTP 200 | Warmup Script |
| Public Status Page | `GET /status/{org-slug}` | HTTP 200, public UI | Browser |
| Grafana OTLP Ingestion | Grafana Cloud Loki Explorer and `demo_http_requests_total` | Logs/metrics are recent after accepted traffic | Grafana UI |
| Demo Shop Fault Switch | `POST /faults/error_spike` with `{"enabled": true}` | HTTP 200, alert triggered | Manual / Script |
| Persona Authentication | `/login` with demo credentials | JWT issued, dashboard loads | Browser |
| Database Backup | `scripts/db-backup.sh` | A fresh dump in `backups/` before the demo | Team |
| Local Compose Fallback | `docker compose --profile app up -d` | Images cached and operational | Local machine |

## Database backup and restore

`scripts/db-backup.sh` saves the application schema (`incident`) and the pgvector extension it uses
to `backups/settle-incident-<where>-<UTC time>.dump`. Supabase's own schemas and `public` are not
included. It reads the backend's `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` (or `DATABASE_URL`),
runs `pg_dump` in a Docker image that matches the server's version, and never prints a credential.
Use the direct connection or Supabase's session pooler (port 5432), not the transaction pooler (6543).
Dumps hold users' emails and password hashes: `backups/` is ignored by git; keep them off shared drives.

`scripts/db-restore.sh <file> --confirm <database-name>` drops the `incident` schema and rebuilds it
from the backup in one transaction, so a failed restore leaves the database as it was. A target that
is not on this machine (Supabase) also needs `--production`. Stop the backend while it runs; Flyway
validates the restored history on the next start.

## Script regression checks

Run `bash scripts/test_demo_warmup.sh`. Its curl stub contacts no deployed services and verifies
healthy exit 0, each required service returning 503, rejected traffic (401/422), connection failure,
retry recovery, accepted 202 traffic, and default/custom internal targets. These checks also run
in the CI **Demo warm-up checks** job. `bash scripts/test_db_backup.sh` checks the backup and
restore scripts against a throwaway database (CI job **Database backup checks**).

Verified on 2026-11-20 at commit `55fbb07fad0e7a968e916ffe9fe48363f58208f8`: all 11 checks passed.
Healthy/recovered health and accepted traffic exited 0; each required service's 503, traffic 401/422,
and curl connection failure exited 1. These are deterministic stub-curl results, not a deployed
readiness measurement.
