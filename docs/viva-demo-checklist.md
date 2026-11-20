# Viva Demo Day-Before Checklist

This document details the operational verification and readiness procedures for the Settle
(IT314 Group 2) viva demonstration. It addresses the realities and limitations of running on cloud
free tiers (Supabase, Render, and Grafana Cloud) and provides a step-by-step checklist to guarantee a
seamless, zero-downtime evaluation.

## Free-Tier Constraints and Mitigations

Our production deployment runs entirely on free-tier infrastructure. Each provider imposes specific
inactivity constraints that can disrupt a live evaluation if not mitigated beforehand:

| Service | Free-Tier Constraint | Failure Mode During Demo | Mitigation Strategy |
| --- | --- | --- | --- |
| **Supabase** | Free PostgreSQL projects auto-pause after 7 days of inactivity. | Database connection refused (`500 Internal Server Error`). Restoring takes 1–3 minutes via the dashboard. | Automated keep-alive workflow ([`.github/workflows/keep-alive.yml`](../.github/workflows/keep-alive.yml)) queries the database twice daily via `/actuator/health`. Manual dashboard confirmation 24 hours prior. |
| **Render** | Free web instances spin down after 15 minutes of inactivity. | First HTTP request incurs a 50–90 second cold start delay, causing apparent frontend hanging or gateway timeouts. | The 12-hour cron keeps Supabase and Grafana alive, but does **not** keep Render awake for a live demo. The [`scripts/demo-warmup.sh`](../scripts/demo-warmup.sh) script retries until HTTP 200 is confirmed; run this manual warm-up right before the demo (T-15m). |
| **Grafana Cloud** | Prometheus metrics and Loki logs have a strict 14-day retention limit. | Incident investigation and telemetry dashboards show "No data" if no traffic has occurred recently. | Keep-alive workflow and warmup script invoke Demo Shop traffic generator (`POST /traffic/start`) to emit fresh OTLP telemetry spanning checkout, payments, and search. |
| **Vercel** | Edge functions and static assets may experience initial cache misses. | Minor initial asset loading delay on first browser page load. | Warm-up script curls root `/` and public status page routes before presentation. |

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

2. **Run the Automated Warm-Up Script:**
   - From the repository root, run the warm-up script targeting production:
     ```bash
     BACKEND_URL=https://incident-backend-f340.onrender.com \
     FRONTEND_URL=https://dev-ops-incident-management-system.vercel.app \
     DEMO_SHOP_URL=${DEMO_SHOP_URL:-http://localhost:8001} \
     DEMO_SHOP_CONTROL_TOKEN=$DEMO_SHOP_CONTROL_TOKEN \
     ./scripts/demo-warmup.sh
     ```
   - Alternatively, trigger the **Demo Keep-Alive and Telemetry Warmup** workflow manually in GitHub Actions via `workflow_dispatch` (reads URLs from repository variables `vars.RENDER_BACKEND_URL`, `vars.VERCEL_FRONTEND_URL`, `vars.DEMO_SHOP_URL`).

3. **Inspect Render Deployment Logs:**
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
     sum(rate(http_requests_total[5m]))
     ```
   - Verify that data timestamps are within the last 30 minutes.

5. **Verify User Accounts and Seed Data:**
   - Open the production frontend ([`https://dev-ops-incident-management-system.vercel.app`](https://dev-ops-incident-management-system.vercel.app)).
   - Test login credentials for the standard demo personas:
     - Admin: `admin@settle.local` / `DevPassw0rd!`
     - Engineer: `engineer@settle.local` / `DevPassw0rd!`
     - Viewer: `viewer@settle.local` / `DevPassw0rd!`
   - Verify that the Service Catalog displays the registered services (e.g., `checkout`, `payments`, `auth`).

---

### T-2 Hours: Morning-of-Viva Check

1. **Trigger Secondary Keep-Alive:**
   - Re-run `./scripts/demo-warmup.sh` or trigger the GitHub Actions workflow to refresh Grafana metrics.
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
   docker compose --profile app --profile demo up -d
   ```
3. Verify local endpoints:
   - Frontend: `http://localhost:8081`
   - Backend API: `http://localhost:8080/actuator/health`
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
| Database Connectivity | Verified via `/actuator/health` | `db` component UP | Warmup Script |
| Frontend Route Response | `GET /` on `https://dev-ops-incident-management-system.vercel.app` | HTTP 200 | Warmup Script |
| Public Status Page | `GET /status/{org-slug}` | HTTP 200, public UI | Browser |
| Grafana OTLP Ingestion | Grafana Cloud Loki Explorer | Logs timestamped < 1h | Warmup Script / UI |
| Demo Shop Fault Switch | `POST /faults/error_spike` with `{"enabled": true}` | HTTP 200, alert triggered | Manual / Script |
| Persona Authentication | `/login` with demo credentials | JWT issued, dashboard loads | Browser |
| Local Compose Fallback | `docker compose --profile app up -d` | Images cached and operational | Local machine |
