# Group 2 — Project Guide

## What we are building

A reliability platform for software teams, working name **Settle**. It has two halves:
- **Incident response, in the style of incident.io:** declare and assign incidents, track SLAs, run
  the timeline, and write postmortems.
- **Monitoring, in the style of Better Stack:** uptime checks, logs and metrics, a service map, and
  an AI SRE that suggests causes from evidence.

We define the whole goal now and build it sprint by sprint. Every sprint ships something that works
end to end on the live site.

The course brief: "A platform for software teams to register services, report and assign
incidents, track severity and SLAs, monitor service health, and generate post-incident reports."

Elicitation so far is one survey with few respondents (see `epics-and-conflicts.md` §2).
Interviews and an online forum post are planned. Requirements change only through
`REQUIREMENTS.md` and the decision log below.

## Documents

| File | Purpose |
| --- | --- |
| [../README.md](../README.md) | Run, test, and deploy; live links |
| [../AGENTS.md](../AGENTS.md) | Rules every change follows |
| [PROJECT.md](PROJECT.md) | Product, scope, stack, team, and the decision log |
| [FEATURES.md](FEATURES.md) | Every feature, how it is used, its requirement, and its sprint |
| [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md) | Architecture and the detailed incident rules |
| [REQUIREMENTS.md](REQUIREMENTS.md) | FRs, NFRs, and DRs (the current version; `requirements/Group-2.pdf` is the submitted one) |
| [user-stories.md](user-stories.md) | User stories with acceptance criteria |
| [epics-and-conflicts.md](epics-and-conflicts.md) | Epics, survey findings, and conflict resolutions |
| [sprint-plan.md](sprint-plan.md) | Roadmap and sprint records |
| [plans/](plans/) | The implementation plan (who builds what), plus the [AI/ML roadmap](plans/ai-ml-roadmap.md) (AI features, AI SRE, MCP server) |
| [TESTING_AND_WORKFLOW.md](TESTING_AND_WORKFLOW.md) | How we test, track, and review work |
| [testing/](testing/) | The [test plan](testing/TEST_PLAN.md), [test cases](testing/TEST_CASES.md), and [test report](testing/TEST_REPORT.md) |
| [TRACEABILITY.csv](TRACEABILITY.csv) | Each FR sub-ID, its check, and its status |

## Scope

**Core (must work first):**
- login and roles;
- the service catalog with SLA targets and on-call;
- incidents, created by hand or from alerts, with deduplication, assignment, notifications, and
  escalation;
- status changes, SLA clocks, and the timeline;
- postmortems with follow-ups;
- the dashboard and history search;
- service health monitoring.

**Extensions (added sprint by sprint; details in [FEATURES.md](FEATURES.md) and the [AI/ML roadmap](plans/ai-ml-roadmap.md)):**
- uptime monitors from several regions, and heartbeats;
- logs and metrics;
- a service map and runbooks;
- deploy tracking;
- a public status page;
- an AI SRE (similar incidents, evidence-based suggestions, a postmortem draft) and an MCP server.

**Not planned:**
- automatic remediation;
- recovery that resolves an incident by itself;
- SLA pauses and business-hours calendars;
- automatic on-call rotations;
- automatic alert grouping across services.

**Choices that keep it simple:**
- One backend and one database. RabbitMQ is added only through an outbox (D24).
- Incidents are assigned to a person. Teams own services.
- Alerts are matched only within one service, by an explicit key.
- SLA targets are copied when an incident is created, and never change for that incident.
- Notifications are in-app first.

## Stack

| Area | Choice |
| --- | --- |
| Backend | Java 21, Spring Boot 4.1, Spring Data JPA with HikariCP (D43), Spring Security, Validation, Bucket4j rate limits (D42) |
| Database | PostgreSQL 16, Flyway |
| Frontend | React, TypeScript, Vite, TanStack Query, Zod, Tailwind and shadcn/ui |
| Tests | JUnit, Mockito where useful, Testcontainers for database behavior, Vitest, Cypress |
| Quality and delivery | PIT for selected business rules; GitHub Actions; Docker Compose with nginx as the entry point (D41) |
| Hosting | Vercel (frontend), Render (backend), Supabase (PostgreSQL), UptimeRobot (D25) |
| AI | Python, FastAPI; Ollama by default (D28) |

Use Spring Boot 4.1 with Java 21 for the new project. Pin a stable 4.1 patch in the build file when scaffolding and use compatible dependencies. Follow Boot 4 documentation when older tutorials differ. Recharts is only needed if we add charts.

Version check, 22 September 2026: the [official project page](https://spring.io/projects/spring-boot) lists 4.1, and its [system requirements](https://docs.spring.io/spring-boot/system-requirements.html) include Java 21. Record any later version change here with its concrete reason; do not quietly fall back to 3.x.

## Team

| Student ID | Name | Tracks |
| --- | --- | --- |
| 202401041 | Christian Shane Prashant | Frontend, Backend, DevOps, Reviewer |
| 202401051 | Diwan Shlok Dineshkumar | Frontend, Backend, AI/ML |
| 202401063 | Gohel Himanshu Shaileshbhai | AI/ML |
| 202401029 | Bhatiya Dhruva Jiteshbhai | AI/ML |
| 202401026 | Bhakti Sudhir Patolia | AI/ML |
| 202401044 | Darji Utsav Girish | Backend |
| 202401021 | Bechara Utsav Bhaveshbhai | Backend |
| 202401014 | Manan Amreliya | Backend |
| 202401070 | Jada Manush Alpeshbhai | Backend |
| 202401033 | Bhungaliya Het Bhaveshbhai | Backend |

There is no separate testing track: everyone writes the tests for their own work. Work is assigned
per task as a GitHub issue. Shane reviews and merges pull requests. Use Slack for team
communication, and GitHub for issues, code, and reviews.

## Short decision log

Selected means a design choice the team adopted, not completed stakeholder validation. Open means the proposed default still needs a team decision. Owners and review dates below are suggested planning assignments, not agreed deadlines. Dates on selected rows record this document revision. Keep the old D identifiers so earlier discussions remain traceable.

| ID | Decision or open question | Status / working default | Suggested owner | Review or recorded date |
| --- | --- | --- | --- | --- |
| D02 | Backend version | Selected: Boot 4.1 and Java 21. Scaffolded on patch 4.1.1; the build targets release 21, so a newer JDK compiles against that level and CI pins 21 | Team 2 | Recorded 2026-09-22 |
| D04 / D18 | Who may read and change incidents? | Open: shared internal reads; Engineer/Admin response; Admin-only configuration; own notifications only | Team 2 | Review 2026-09-23 |
| D06 | What counts as changed content for a repeated event ID? | Open: define the meaningful payload fields covered by the fingerprint before intake tests | Team 2 | Review 2026-09-24 |
| D07 | When do separate alerts share an incident? | Open: exact source + service + correlation key while active; no cross-service grouping | Team 2 | Review 2026-09-24 |
| D09 / D10 | Does changing severity change an existing SLA target? | Open: copy targets at creation and keep them fixed; severity changes priority only | Team 1 | Review 2026-09-23 |
| D12 | How are time slots interpreted? | Open: UTC storage, one display zone to choose, start inclusive/end exclusive, no overlap for one service | Team 2 | Review 2026-09-23 |
| D13 | Who owns an escalated incident; do comments contend with acknowledgement? | Selected: escalation only notifies; first accepted acknowledger becomes assignee; comments insert separately without incrementing Incident version | Team 1 | Recorded 2026-09-22 |
| D14 | How does escalation catch up after restart? | Open: process one overdue step once, then give the next contact a full wait | Team 1 | Review 2026-09-24 |
| D17 | What is needed before closure? | Open: recovery note plus a saved, reviewed basic postmortem for every incident; Engineer/Admin may review; action items can remain open | Team 1 + Team 3 | Review 2026-09-25 |
| D19 | Which older requirement details remain in scope? | Open: reconcile narrowed dashboard filters/averages, team assignment, catalog criticality, and any other differences flagged in the CSV | All three teams | Review 2026-09-25 |
| D20 | Application roles | Selected: `ADMIN`, `ENGINEER`, `VIEWER` | Team 1 | Recorded 2026-10-11 |
| D21 | How do users authenticate? | Selected: stateless JWT (HS256) sent as a Bearer header and kept in `sessionStorage`. A per-user `token_version` revokes old tokens on logout or role change. CSRF is disabled because the browser never sends the token on its own | Shane | Recorded 2026-10-11 |
| D22 | What does creating an incident record in Sprint 1? | Selected: the incident, its copied SLA targets, a timeline entry, and an audit entry, in one transaction (E4a, epics Decision 3) | Utsav B | Recorded 2026-10-11 |
| D23 | Service criticality and default targets | Selected: criticality `LOW`/`MEDIUM`/`HIGH`/`CRITICAL`; default response targets SEV1 15, SEV2 60, SEV3 240, SEV4 1440 minutes (C7); resolution targets entered by the admin | Utsav B | Recorded 2026-10-20 |
| D24 | Messaging and cache | Selected: RabbitMQ through a transactional outbox, added when the first async consumer lands; Redis optional, for live updates (HLD v2) | Shane | Recorded 2026-10-11 |
| D25 | Where is the app hosted? | Selected: frontend on Vercel, backend on Render (Docker), PostgreSQL on Supabase, UptimeRobot pinging `/actuator/health` to keep the backend awake | Shane | Recorded 2026-10-11 |
| D26 | Which database schema holds our tables? | Selected: `incident`, managed by Flyway. Supabase keeps its own objects in `public`, and its Data API only exposes `public` | Shane | Recorded 2026-10-12 |
| D27 | Is development seed data loaded by default? | Selected: no. Flyway reads only `classpath:db/migration` unless `FLYWAY_LOCATIONS` also names `classpath:db/devdata`, so production never receives the dev users (Sprint 1 plan, Database) | Shlok | Recorded 2026-10-12 |
| D28 | Where do AI models run? | Selected: self-hosted through Ollama, on a team machine or our own container. Incident data is not sent to external AI providers (NFR-12.1) | Himanshu | Recorded 2026-10-13 |
| D29 | Is service health monitoring required? | Selected: Must (FR-22). The project brief lists "monitor service health"; recovery never resolves an incident automatically | Shane | Recorded 2026-10-13 |
| D30 | Which requirements document is current? | Selected: `docs/REQUIREMENTS.md`. `Group-2.pdf` is the submitted version; IDs never change | Shane | Recorded 2026-10-13 |
| D31 | How do alert senders authenticate? | Open: one bearer token per registered tool, shown once, stored as a SHA-256 hash, revocable. Replaces the source signature in PROJECT_STRUCTURE §6 | Shane | Review in Sprint 2 planning |
| D32 | Where do alerts arrive? | Open: `POST /webhooks/alerts`, outside `/api` and outside JWT login (HLD v2) | Shane | Review in Sprint 2 planning |
| D33 | How are notifications stored in Sprint 2? | Open: rows written in the same transaction as the incident change; the outbox and RabbitMQ arrive in Sprint 3 | Shane | Review in Sprint 2 planning |
| D34 | Where do logs and metrics live? | Open: Grafana Cloud free tier (Prometheus and Loki, 14-day retention); the backend holds the read token and runs every query | Shane | Review in Sprint 2 planning |
| D35 | Where do probes run? | Open: 2–3 free schedulers in different regions, chosen after a Sprint 2 spike | Shane | Review in Sprint 2 |
| D36 | When is a monitor down? | Open: only when at least 2 regions agree | Team | Review in Sprint 3 planning |
| D37 | Which AI models may we use? | Open: local Ollama by default; a hosted model on a free tier, off by default and on demo-shop data only. Needs NFR-12.1 reworded | Himanshu | Review in Sprint 3 planning |
| D38 | What may the MCP server do? | Open: personal access tokens; read tools first; `add_comment` and `acknowledge` later, as the user and audited; never status changes, declaring, or closing | Shane | Review in Sprint 4 planning |
| D39 | Which incident roles exist? | Open: Lead (the assignee, replaces Commander), Comms, Ops; Scribe dropped (FEATURES.md, Incident roles) | Team | Review in Sprint 3 planning |
| D40 | What does the demo shop simulate? | Open: payments, checkout, and search with fault switches; each switch is a labelled scenario for testing and AI evaluation | Team | Review in Sprint 2 planning |
| D41 | Where does nginx run? | Selected: as the entry point of the Docker Compose stack (serves the UI, proxies `/api` and `/webhooks`, security headers, gzip, a second rate limit). Production stays on Vercel and Render: on Render's free tier the backend URL is public, so nginx there could be bypassed | Shane | Recorded 2026-10-17 |
| D42 | How are callers rate-limited? | Selected: in the backend with Bucket4j (NFR-04.4). Login about 5 a minute per IP and username; `/webhooks/alerts` about 50 a second per tool token; MCP about 60 a minute per personal access token. Over the limit returns 429 with `Retry-After`. Buckets live in memory while there is one backend instance | Shane | Recorded 2026-10-17 |
| D43 | How is the database connection pool set? | Selected: HikariCP, Spring Boot's default, with pool size and timeouts from environment variables. The pool stays below the Supabase session pooler's limit | Shane | Recorded 2026-10-17 |

Before implementing an affected rule, its owner should record the team's decision, actual date, and Slack or issue reference. Optional features need not block EPIC 1. If a suggested review date does not fit the sprint, change it explicitly rather than presenting the review as completed.
