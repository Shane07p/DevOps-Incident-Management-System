# Settle Performance Testing with k6

This directory contains automated performance and load testing scripts for **Settle Incident Management System**, validating requirements **NFR-01.1** and **NFR-01.2** using [k6](https://k6.io/).

---

## 1. Requirements & Thresholds

| Identifier | Target Endpoint | Workload Profile | Success Criteria (Thresholds) |
|---|---|---|---|
| **NFR-01.1** | `GET /api/incidents`<br>`GET /api/incidents/{id}` | 1,000 seeded incidents in database<br>10 concurrent VUs<br>Ramp 10s $\rightarrow$ Steady 60s $\rightarrow$ Ramp 10s | 95th percentile response time $< 2,000\text{ ms}$ (`p(95) < 2s`)<br>HTTP failure rate $< 1\%$ |
| **NFR-01.2** | `POST /webhooks/alerts` | Constant arrival rate: 10 alerts/sec for 30s<br>Pre-allocated 10 VUs, max 20 VUs | 95th percentile response time $< 1,000\text{ ms}$ (`p(95) < 1s`)<br>HTTP failure rate $< 1\%$ |

---

## 2. Directory Structure

```
perf/
├── nfr-01-1-incidents.js       # k6 script for incident board and detail latency (NFR-01.1)
├── nfr-01-2-alerts.js          # k6 script for 10 alerts/sec webhook intake (NFR-01.2)
├── seed_1000_incidents.sql     # Idempotent PostgreSQL seed for 1,000 test incidents
└── README.md                   # Execution guide and reference (this document)
```

---

## 3. Prerequisites

You can run the tests using either **native k6** or **Docker Desktop**.

- **Option A (Native k6 on Windows):**
  - Install via Chocolatey: `choco install k6`
  - Or via Winget: `winget install k6`
  - Verify with: `k6 version`
- **Option B (Docker Desktop):**
  - Use the official image: `grafana/k6:latest`

---

## 4. Environment Variables Reference

| Variable | Default Value | Description |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | Base URL of the backend application under test. |
| `ADMIN_EMAIL` | `admin@settle.local` | Admin user email for acquiring the JWT session token. |
| `ADMIN_PASSWORD` | `DevPassw0rd!` | Admin user password. |
| `TOOL_TOKEN` | *(auto-registered if unset)* | Bearer token for incoming alert webhooks (starts with `tt_`). |
| `SERVICE_NAME` | `Payments` | Target service in the catalog to attribute test alerts to. |

---

## 5. Step 1: Database Seeding (NFR-01.1 Precondition)

Before running the incident latency test (`nfr-01-1-incidents.js`), the database must contain at least 1,000 incidents.

### Local Docker Compose
When the Docker Compose database service (`db`) is running:
```powershell
# Using PowerShell:
Get-Content perf/seed_1000_incidents.sql | docker compose exec -T db psql -U incident -d incident
```

### Local PostgreSQL Service
If running PostgreSQL locally on port 5433:
```powershell
psql -h localhost -p 5433 -U incident -d incident -f perf/seed_1000_incidents.sql
```

> **Note on Idempotency:** The SQL script inspects existing incidents. If 1,000 `Perf-Test Incident #` records are already present, the script skips insertion cleanly without error or duplicate data.

---

## 6. Step 2: Running the Performance Tests

### 6.1 Windows Docker Desktop Networking Note
> [!IMPORTANT]
> On Windows Docker Desktop, `--network=host` **does not** bind to the host's Windows loopback adapter (it attaches to the WSL2 VM network).
> To reach services published on your host machine from a Docker container:
> - Use **`host.docker.internal`** instead of `localhost` (e.g. `http://host.docker.internal:8080`).
> - Or attach directly to the Docker Compose network (e.g. `--network=devops-incident-management-system_default`) and target `http://app:8080`.

---

### 6.2 Running NFR-01.1 (Incident Board & Detail Latency)

#### With Native k6 (Recommended if installed):
```powershell
k6 run perf/nfr-01-1-incidents.js -e BASE_URL=http://localhost:8080
```

#### With Docker Desktop:
```powershell
# Option A: Attaching to the Compose network (Recommended for Docker Desktop):
docker run --rm -i --network devops-incident-management-system_default -v "${PWD}/perf:/perf" grafana/k6 run /perf/nfr-01-1-incidents.js -e BASE_URL=http://app:8080

# Option B: Connecting via host loopback (host port 8082):
docker run --rm -i -v "${PWD}/perf:/perf" grafana/k6 run /perf/nfr-01-1-incidents.js -e BASE_URL=http://host.docker.internal:8082
```

---

### 6.3 Running NFR-01.2 (Alert Ingestion at 10 alerts/sec)

#### Option 1: Auto-Provisioning Tool Token (Local Dev)
If `TOOL_TOKEN` is omitted, the script logs in using `admin@settle.local`, registers a temporary tool token (`POST /api/tools`), runs the 10 alerts/sec workload, and automatically revokes the tool during teardown:
```powershell
# Native k6:
k6 run perf/nfr-01-2-alerts.js -e BASE_URL=http://localhost:8080

# Docker k6 (Compose network):
docker run --rm -i --network devops-incident-management-system_default -v "${PWD}/perf:/perf" grafana/k6 run /perf/nfr-01-2-alerts.js -e BASE_URL=http://app:8080

# Docker k6 (host port 8082):
docker run --rm -i -v "${PWD}/perf:/perf" grafana/k6 run /perf/nfr-01-2-alerts.js -e BASE_URL=http://host.docker.internal:8082
```

#### Option 2: Explicit Tool Token
```powershell
# Native k6:
k6 run perf/nfr-01-2-alerts.js -e BASE_URL=http://localhost:8082 -e TOOL_TOKEN=<your_tool_token>

# Docker k6 (Compose network):
docker run --rm -i --network devops-incident-management-system_default -v "${PWD}/perf:/perf" grafana/k6 run /perf/nfr-01-2-alerts.js -e BASE_URL=http://app:8080 -e TOOL_TOKEN=<your_tool_token>

# Docker k6 (host port 8082):
docker run --rm -i -v "${PWD}/perf:/perf" grafana/k6 run /perf/nfr-01-2-alerts.js -e BASE_URL=http://host.docker.internal:8082 -e TOOL_TOKEN=<your_tool_token>
```

---

## 7. Running Against Deployed Environments

> [!CAUTION]
> **Safety Rules for Deployed Testing:**
> 1. **NEVER** run `seed_1000_incidents.sql` against a production or shared staging database.
> 2. **NEVER** hardcode credentials or tokens in git.
> 3. Use an authorized, dedicated monitoring tool token registered for performance verification.
> 4. Keep testing non-destructive: NFR-01.1 is read-only; for NFR-01.2 use a dedicated test service or short bursts.

```powershell
# Test incident latency against deployed backend:
k6 run perf/nfr-01-1-incidents.js `
  -e BASE_URL=https://<your-backend-app>.onrender.com `
  -e ADMIN_EMAIL=<authorized_admin_email> `
  -e ADMIN_PASSWORD=<authorized_admin_password>

# Test alert ingestion against deployed backend:
k6 run perf/nfr-01-2-alerts.js `
  -e BASE_URL=https://<your-backend-app>.onrender.com `
  -e TOOL_TOKEN=<authorized_tool_token>
```

---

## 8. Metrics Interpretation & Pass/Fail Criteria

When k6 completes, inspect the output summary:

```text
✓ http_req_duration{endpoint:incidents_board}......: p(95)=<val>ms   [ < 2000ms  ✓ ]
✓ http_req_duration{endpoint:incident_detail}......: p(95)=<val>ms   [ < 2000ms  ✓ ]
✓ alert_intake_latency.............................: p(95)=<val>ms   [ < 1000ms  ✓ ]
✓ http_req_failed..................................: <val>%          [ < 1%      ✓ ]
```

- **Threshold Passed (`✓`):** All 95th percentile metrics are under the required ceiling, and error rate is under 1%.
- **Threshold Failed (`✗`):** The system exceeded latency bounds or suffered connection errors.

---

## 9. Cleanup & Data Retention

Performance test incidents generated by `perf/seed_1000_incidents.sql` can safely remain in the database across subsequent runs and be reused. The seed script is strictly idempotent:
- It checks existing records matching `Perf-Test Incident #%` in the target organization.
- If 1,000 incidents already exist, it skips insertion cleanly without creating duplicates or modifying existing data.
- If a partial count exists, it only inserts the remaining difference up to exactly 1,000.

Leaving seeded data in place avoids unnecessary churn and allows repeated execution of benchmark scripts against a stable dataset.

> [!CAUTION]
> **Do not use `docker compose down -v` for routine cleanup.**
> Tearing down Docker volumes destroys the persistent database state, wiping all schema migrations, user accounts, baseline service configurations, and dev seeds.

