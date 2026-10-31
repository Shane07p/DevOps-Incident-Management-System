# Incident Management System

IT314 course project, Group 2 (working name **Settle**). A reliability platform for software
teams:
- incident response in the style of incident.io;
- monitoring in the style of Better Stack;

built sprint by sprint.

The documents in [docs/](docs/) are the specification. Read [docs/PROJECT.md](docs/PROJECT.md)
first. Every planned feature is in [docs/FEATURES.md](docs/FEATURES.md), and the AI features in
the [AI/ML roadmap](docs/plans/ai-ml-roadmap.md).

## Status

Sprint 1 is complete; Sprints 2 and 3 are in progress. Each work item's issue and status is in the
[implementation plan](docs/plans/2026-10-17-implementation-plan.md#work-items).

| Part | State |
| --- | --- |
| Foundations: Spring Boot 4.1, Flyway (migrations V1–V8), Testcontainers, Swagger, CI, CodeQL, Dependabot, hosting | Done |
| Login, roles, and user administration | Done |
| Service catalog with SLA targets; incidents (create, list, detail) | Done |
| On-call schedules, with assignment at creation | Done |
| Tool tokens, the alert webhook, deduplication and grouping, and the alert inbox | Done |
| Rate limits on login and the alert webhook | Done |
| Escalation policies and the escalation worker; events wait in the outbox until RabbitMQ | Done |
| Pages: login, home, users, tools, services, incidents, on-call, escalation policies, alerts | Done |
| In-app notifications for assignments and escalations | Done |
| Monitors and heartbeats, acknowledgement, status and severity changes, reassignment, comments | Not started (Sprints 2 and 3) |
| AI service: `/health`, `/ready`, `/embed` (Ollama embeddings), stub `/suggest`, log-pattern evidence | Done |
| Demo shop v1 with five fault switches | Done |

## Live

| What | URL |
| --- | --- |
| App | https://dev-ops-incident-management-system.vercel.app |
| Backend health | https://incident-backend-f340.onrender.com/actuator/health |
| API docs (Swagger UI) | https://incident-backend-f340.onrender.com/swagger-ui.html |

## Requirements

| Tool | Version | Note |
| --- | --- | --- |
| JDK | 21 or newer | The build targets release 21; a newer JDK compiles against that level |
| Node | 24 | Matches CI |
| Docker | Any recent version | Needed for the database and the Testcontainers tests |

Maven is not required. Use the `mvnw` wrapper in `backend/`.

## Getting started

```bash
cp .env.example .env
docker compose up -d db       # PostgreSQL 16 on localhost:5433

cd backend && ./mvnw spring-boot:run    # http://localhost:8080
cd frontend && npm ci && npm run dev        # http://localhost:5173
```

The frontend dev server proxies `/api` to `http://localhost:8080`, so both run on their own
port without CORS configuration. API docs are at http://localhost:8080/swagger-ui.html.

Four things worth knowing before you change any setting:

- The database is on **5433**, not 5432. A locally installed PostgreSQL service usually owns
  5432 and would shadow the container, which shows up as a confusing password failure.
- `.env` is read by Docker Compose. The backend reads real environment variables, so if you
  change a default, export it too or use the `app` profile below. Leave the defaults alone and
  everything lines up on its own.
- PostgreSQL applies `POSTGRES_PASSWORD` only when it first initialises its data directory. If
  you change the password later, `docker compose down -v` to drop the volume and local data.
- Our tables live in the `incident` schema, not `public`. Look there in any database tool.

### Dev seed data

The seed (users `admin`, `engineer`, `viewer`, all with password `DevPassw0rd!`, and a `Payments`
service) is off by default (D27), so production and the test suite never load it. Turn it on per
command, never by exporting the variable in your shell profile:

```bash
FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/devdata ./mvnw spring-boot:run
```

For `docker compose --profile app`, uncomment `FLYWAY_LOCATIONS` in `.env` instead.

Turning the seed off again is fine: the same database still starts, but the seed's users and
service stay in it. For a database without them, or to try the seed without touching your own,
use a throwaway database instead of resetting your volume:

```bash
docker run --rm -d --name incident-scratch -p 5434:5432 \
  -e POSTGRES_DB=incident -e POSTGRES_USER=incident -e POSTGRES_PASSWORD=incident postgres:16

# Without the seed
DB_URL=jdbc:postgresql://localhost:5434/incident ./mvnw spring-boot:run
# Or with it
DB_URL=jdbc:postgresql://localhost:5434/incident \
  FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/devdata ./mvnw spring-boot:run

docker stop incident-scratch    # removes the container and its data
```

To run the packaged backend and the built UI against the same database instead:

```bash
docker compose --profile app up --build
```

Open http://localhost:8081. nginx serves the UI there and proxies `/api` and `/webhooks` to the
backend, the way the stack runs in Docker (D41).

## Tests

```bash
cd backend  && ./mvnw verify          # JUnit + Testcontainers (Docker must be running)
cd backend  && ./mvnw test-compile org.pitest:pitest-maven:mutationCoverage   # not in CI: on demand
cd frontend && npm test               # Vitest
cd frontend && npm run cypress:run    # end-to-end, needs both servers; the first scenario is #57 (Sprint 6)
```

## Layout

| Path | Contents |
| --- | --- |
| `backend/` | Spring Boot application and Java tests |
| `backend/src/main/resources/db/migration/` | Flyway migrations |
| `backend/src/main/resources/db/devdata/` | Opt-in dev seed data |
| `frontend/` | React screens, components, and frontend tests |
| `frontend/vercel.json` | Vercel routing: `/api` to the Render backend, other paths to the app |
| `docs/` | Design documents, the requirements PDF, diagrams, and sprint notes |
| `.github/workflows/` | CI |
| `compose.yaml` | Local database and packaged application |
| `.env.example` | Example settings, no real credentials |
| `ai-service/` | Python and FastAPI: embeddings through Ollama, the stub `/suggest`, and evidence functions |
| `demo-shop/` | Demo Shop v1: retail simulation with 5 fault switches and OpenTelemetry (Sprint 2) |

`ai-service/` (Python, FastAPI, Ollama) and `demo-shop/` run locally or in optional Compose profiles; see the [AI/ML roadmap](docs/plans/ai-ml-roadmap.md) and [demo-shop/README.md](demo-shop/README.md).

## Team and plans

The team and their tracks are in [docs/PROJECT.md](docs/PROJECT.md#team). Each sprint's work is in
[docs/plans/](docs/plans/).

## Deployment

Merging to `main` deploys automatically once CI passes:

| Part | Host | Notes |
| --- | --- | --- |
| Frontend | Vercel | Every pull request also gets a preview link from the Vercel bot |
| Backend | Render (Docker, free tier) | Settings come from environment variables on Render: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET` (`openssl rand -base64 32`), `BOOTSTRAP_ADMIN_USERNAME` and `BOOTSTRAP_ADMIN_PASSWORD` (create the first admin while no users exist), `JAVA_TOOL_OPTIONS`. Optional: the `DB_POOL_*` and `RATE_LIMIT_*` settings in `.env.example`. `FLYWAY_LOCATIONS` stays unset or `classpath:db/migration`, so the dev seed never loads |
| Database | Supabase (PostgreSQL) | Reached through the session pooler; tables in the `incident` schema |
| Uptime | UptimeRobot | Pings `/actuator/health` every 10 minutes, which also keeps Render's free tier awake |

Secrets live only in Render's environment variables, never in the repository.

## Working agreement

Branch from `main`, keep commits attributable to their author, open a focused pull request,
and Shane reviews and merges it. CI must pass before merge. `AGENTS.md` holds the
conventions that apply to both people and coding agents working in this repository.
