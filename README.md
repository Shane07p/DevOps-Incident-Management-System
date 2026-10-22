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

Foundations, deployment, and login are in place. The service catalog, incidents, and the
frontend pages are next.

| Part | State |
| --- | --- |
| Repository, `.gitignore`, `.gitattributes` | Done |
| Backend: Spring Boot 4.1.1, Flyway, Testcontainers, error handling, Swagger/OpenAPI | Done |
| Frontend: Vite + React + TypeScript, Tailwind, TanStack Query, Vitest, Cypress | Done |
| PostgreSQL 16 in Docker Compose | Done |
| CI (build and test both applications), CodeQL, Dependabot | Done |
| Deployment: Vercel, Render, Supabase, UptimeRobot | Done |
| Migrations V1–V3 and opt-in dev seed data | Done |
| Login and roles (FR-01) | Login API done; user administration next |
| Frontend: shared UI components and the API client | Done |
| Frontend pages (login, services, incidents, users) | Not started |
| Service catalog and incidents API | In progress |
| AI service skeleton (FastAPI, `/health`, stub `/suggest`) | Done |

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

Three things worth knowing before you change any setting:

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
cd backend  && ./mvnw test-compile org.pitest:pitest-maven:mutationCoverage   # on demand
cd frontend && npm test               # Vitest
cd frontend && npm run cypress:run    # end-to-end, needs both servers running
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

`ai-service/` (Python, FastAPI, Ollama) runs locally only; see the
[AI/ML roadmap](docs/plans/ai-ml-roadmap.md).

## Team and plans

The team and their tracks are in [docs/PROJECT.md](docs/PROJECT.md#team). Each sprint's work is in
[docs/plans/](docs/plans/).

## Deployment

Merging to `main` deploys automatically once CI passes:

| Part | Host | Notes |
| --- | --- | --- |
| Frontend | Vercel | Every pull request also gets a preview link from the Vercel bot |
| Backend | Render (Docker, free tier) | Settings come from environment variables on Render: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET` (`openssl rand -base64 32`), `BOOTSTRAP_ADMIN_USERNAME` and `BOOTSTRAP_ADMIN_PASSWORD` (create the first admin while no users exist), `JAVA_TOOL_OPTIONS`. `FLYWAY_LOCATIONS` stays unset or `classpath:db/migration`, so the dev seed never loads |
| Database | Supabase (PostgreSQL) | Reached through the session pooler; tables in the `incident` schema |
| Uptime | UptimeRobot | Pings `/actuator/health` every 10 minutes, which also keeps Render's free tier awake |

Secrets live only in Render's environment variables, never in the repository.

## Working agreement

Branch from `main`, keep commits attributable to their author, open a focused pull request,
and Shane reviews and merges it. CI must pass before merge. `AGENTS.md` holds the
conventions that apply to both people and coding agents working in this repository.
