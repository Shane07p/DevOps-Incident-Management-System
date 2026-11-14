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
`srs.md` and the decision log below.

## Documents

[README.md](../README.md) lists every document, what it holds, and when to open it.

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

**Extensions (added sprint by sprint; details in [features.md](../2-design/features.md) and the [AI/ML roadmap](../2-design/ai-ml-roadmap.md)):**
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
- One backend and one database. RabbitMQ is added only through an outbox, for email and later AI jobs; no Redis while there is one backend instance (D24).
- Incidents are assigned to a person, and each service has one user as its owner. Team ownership is an open question (D19).
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
| D02 | Backend version | Selected: Boot 4.1 and Java 21. Scaffolded on patch 4.1.1, and Dependabot keeps the 4.1 patch current; the build targets release 21, so a newer JDK compiles against that level and CI pins 21 | Team 2 | Recorded 2026-09-22 |
| D04 / D18 | Who may read and change incidents? | Selected, as built since Sprint 1: every signed-in user reads shared incident records; Engineers and Admins respond; only Admins change users, services, on-call, escalation, and tools; notifications are visible only to their recipient. `SecurityMatrixTest` checks every endpoint for every role | Team 2 | Recorded 2026-10-30 (#109, architecture.md §9) |
| D06 | What counts as changed content for a repeated event ID? | Selected: a SHA-256 fingerprint of source, event ID, the resolved service, title, description, severity, correlation key and `details` (map keys sorted, so their order doesn't matter). Arrival time and any other field are left out. Same fingerprint: the earlier result is returned; different: 409 | Team 2 | Recorded 2026-10-29 (#123) |
| D07 | When do separate alerts share an incident? | Selected: the same source, service and correlation key as an incident that is not RESOLVED or CLOSED. No key, no match, or only a finished match opens a new incident; no cross-service grouping. Leading and trailing spaces are ignored | Team 2 | Recorded 2026-10-29 (#123) |
| D09 / D10 | Does changing severity change an existing SLA target? | Selected: no. Targets are copied at creation and stay fixed; a severity change only changes priority and is recorded with a reason (AGENTS.md, architecture.md §8). US-10 follows this | Team 1 | Recorded 2026-10-30 (targets copied since #103; severity changes with #84) |
| D12 | How are time slots interpreted? | Selected: UTC storage; IST (Asia/Kolkata) is the one display zone, and shifts are entered in it; start inclusive, end exclusive; no overlap for one service | Team 2 | Recorded 2026-10-28 (#80) |
| D13 | Who owns an escalated incident; do comments contend with acknowledgement? | Selected: escalation only notifies; first accepted acknowledger becomes assignee; comments insert separately without incrementing Incident version | Team 1 | Recorded 2026-09-22 |
| D14 | How does escalation catch up after restart? | Selected: deadlines are stored, so after a restart the worker runs one overdue step once, then gives the next level its full wait. Each level is notified its own wait after the previous notification (the first, its wait after creation); the fallback contact, the last level's wait after the last level, once | Team 1 | Recorded 2026-10-29 (#85) |
| D17 | What is needed before closure? | Open: recovery note plus a saved, reviewed basic postmortem for every incident; Engineer/Admin may review; action items can remain open. Recorded for follow-ups only: they may be added and stay open after the incident closes (#240, 2026-11-13); the closure rule itself is still open | Team 1 + Team 3 | Decide before the postmortem work (Sprint 5) |
| D19 | Which older requirement details remain in scope? | Open: reconcile narrowed dashboard filters/averages, team assignment, catalog criticality, and any other differences flagged in the CSV | All three teams | Decide before the sprint that builds each item |
| D20 | Application roles | Selected: `ADMIN`, `ENGINEER`, `VIEWER` | Team 1 | Recorded 2026-10-11 |
| D21 | How do users authenticate? | Selected: stateless JWT (HS256) sent as a Bearer header and kept in `sessionStorage`. A per-user `token_version` revokes old tokens on logout or role change. CSRF is disabled because the browser never sends the token on its own | Shane | Recorded 2026-10-11 |
| D22 | What does creating an incident record in Sprint 1? | Selected: the incident, its copied SLA targets, a timeline entry, and an audit entry, in one transaction (E4a, epics Decision 3) | Utsav B | Recorded 2026-10-11 |
| D23 | Service criticality and default targets | Selected: criticality `LOW`/`MEDIUM`/`HIGH`/`CRITICAL`; default response targets SEV1 15, SEV2 60, SEV3 240, SEV4 1440 minutes (C7); resolution targets entered by the admin | Utsav B | Recorded 2026-10-20 |
| D24 | Messaging and cache | Selected: RabbitMQ through a transactional outbox, for work that is slow or may fail: email first (FR-07.2, D47), AI jobs later. Events go to the `settle.events` topic exchange, marked SENT once the broker confirms them, so a consumer may see one twice and dedupes by message id. Each queue's worker runs inside the backend, and a message that keeps failing goes to a failed-messages queue. In-app notifications do not use it (D33), and live updates come from the backend itself (D46). No Redis while there is one backend instance: in-memory caches (Caffeine) are enough; Redis is for sharing state between several instances. Hosted on CloudAMQP's free RabbitMQ plan; unconfigured, events wait in the outbox | Shane | Recorded 2026-10-11; publishing 2026-11-01 (#82); email, AI jobs, and no Redis 2026-11-02 (HLD v4) |
| D25 | Where is the app hosted? | Selected: frontend on Vercel, backend on Render (Docker), PostgreSQL on Supabase, UptimeRobot pinging `/actuator/health` to keep the backend awake | Shane | Recorded 2026-10-11 |
| D26 | Which database schema holds our tables? | Selected: `incident`, managed by Flyway. Supabase keeps its own objects in `public`, and its Data API only exposes `public` | Shane | Recorded 2026-10-12 |
| D27 | Is development seed data loaded by default? | Selected: no. Flyway reads only `classpath:db/migration` unless `FLYWAY_LOCATIONS` also names `classpath:db/devdata`, so production never receives the dev users (Sprint 1 plan, Database) | Shlok | Recorded 2026-10-12 |
| D28 | Where do AI models run? | Selected: self-hosted through Ollama, on a team machine or our own container. Incident data is not sent to external AI providers (NFR-12.1) | Himanshu | Recorded 2026-10-13 |
| D29 | Is service health monitoring required? | Selected: Must (FR-22). The project brief lists "monitor service health"; recovery never resolves an incident automatically | Shane | Recorded 2026-10-13 |
| D30 | Which requirements document is current? | Selected: `docs/1-requirements/srs.md`. `Group-2.pdf` is the submitted version; IDs never change | Shane | Recorded 2026-10-13 |
| D31 | How do alert senders authenticate? | Selected: one bearer token per registered tool, shown once, stored as a SHA-256 hash, revocable. Replaces the source signature in architecture.md §6 | Shane | Recorded 2026-10-26 (#68) |
| D32 | Where do alerts arrive? | Selected: `POST /webhooks/alerts`, outside `/api` and outside JWT login (HLD v2) | Shane | Recorded 2026-10-26 (#68) |
| D33 | How are notifications stored in Sprint 2? | Selected: in-app rows written in the same transaction as the change that causes them (an assignment at creation, an escalation step, the fallback contact), so they roll back together; `createNotification` refuses to run outside a transaction. The outbox and RabbitMQ (#82) are for other channels, starting with email (D24) | Shane | Recorded 2026-10-31 (#70) |
| D34 | Where do logs and metrics live? | Selected: Grafana Cloud free tier (Prometheus and Loki, 14-day retention). The demo shop writes through OTLP with a write-only token (#111). The backend reads with a separate read-only token and builds every query itself, so the browser never sees a token or a raw query (#156). Tokens are environment variables only. **The single server-wide stack is replaced by D49**: the demo shop still writes to our Grafana Cloud, but the backend reads each org's own | Shane | Recorded 2026-11-01 (#75); read side replaced 2026-11-13 by D49 |
| D35 | Where do probes run? | Selected: Grafana Cloud Synthetic Monitoring checks each monitored URL from 2–3 of its locations; its alert rules post to `/webhooks/alerts` with a tool token. Nothing of ours to host. Checks run every 2–5 minutes to stay inside the free tier | Shane | Recorded 2026-11-02 (#88) |
| D36 | When is a monitor down? | Selected: **Down** when at least 2 locations fail (an error or no answer) on 2 checks in a row; **Degraded** when at least 2 locations answer but slower than the monitor's limit (default 2 s) on 3 checks in a row; otherwise Operational. Down raises a critical alert, Degraded a warning; the counts and the limit are per monitor. Written as Grafana alert rules, so one failing location never raises anything | Shane | Recorded 2026-11-02 (#88) |
| D37 | Which AI models may we use? | Open: local Ollama by default; a hosted model on a free tier, off by default and on demo-shop data only. Needs NFR-12.1 reworded | Himanshu | Decide before #91 uses a hosted model |
| D38 | What may the MCP server do? | Selected: each user creates personal access tokens for their own AI tools (Claude, Cursor): shown once, stored as a SHA-256 hash, revocable, valid for 90 days, about 60 requests a minute. A token acts as its owner with the owner's current role, and cannot create or list tokens. The MCP server only calls the REST API with that token. Sprint 4 has read tools only; `add_comment` and `acknowledge` come later, as the user and audited as via MCP. Never status changes, declaring, or closing (FR-23, FR-28) | Shane | Recorded 2026-11-02 (#149) |
| D39 | Which incident roles exist? | Selected: Lead (the assignee; replaces Commander), Comms, and Ops (features.md, Incident roles). Scribe is dropped: the timeline records events by itself. One person may hold several roles, with a warning on SEV1 and SEV2 (FR-05.5) | Shane | Recorded 2026-11-02 (#86) |
| D40 | What does the demo shop simulate? | Selected: payments, checkout, and search, with five fault switches (slow gateway, error spike, bad deploy, queue backlog, expiring certificate). Each switch is a labelled scenario for testing and AI evaluation (#64) | Team | Recorded 2026-10-26 (#111) |
| D41 | Where does nginx run? | Selected: as the entry point of the Docker Compose stack (serves the UI, proxies `/api` and `/webhooks`, security headers, gzip, a second rate limit). Production stays on Vercel and Render: on Render's free tier the backend URL is public, so nginx there could be bypassed | Shane | Recorded 2026-10-17 |
| D42 | How are callers rate-limited? | Selected: in the backend with Bucket4j (NFR-04.4). Login about 5 a minute per IP and username; `/webhooks/alerts` about 50 a second per tool token; MCP about 60 a minute per personal access token. Over the limit returns 429 with `Retry-After`. Buckets live in a bounded in-memory cache while there is one backend instance, and the client address comes from `X-Forwarded-For` only through a trusted proxy (#126) | Shane | Recorded 2026-10-17 |
| D43 | How is the database connection pool set? | Selected: HikariCP, Spring Boot's default, with pool size and timeouts from environment variables. The pool stays below the Supabase session pooler's limit | Shane | Recorded 2026-10-17 |
| D44 | Where do incident embeddings live? | Selected: in our PostgreSQL with pgvector (Supabase supports it), a `vector(768)` column for `nomic-embed-text`, compared by cosine distance (`<=>`), so there is one database with the same backups, transactions, and org scoping. A similarity query is native SQL, which Hibernate's `@TenantId` filter does not see, so it must filter on `org_id` itself (D48). The threshold starts at 0.70 from the retrieval evaluation (#161). Embeddings come from the AI service's `POST /embed`, which is not hosted on Render yet (D37), so Find similar works only where the AI service runs; with it down, similar incidents come back empty (FR-18.4) | Shane | Recorded 2026-11-03 (#149) |
| D45 | What does the public status page show, and who may publish? | Selected: only the services an Admin picks, under a public name: health (Operational, Degraded, or Down; D36) and 90-day uptime, public updates on active incidents, and resolved incidents from the last 90 days. Never internal notes, names, or comments. The incident's Lead or Comms, or an Admin, publishes updates; until incident roles exist (#86), Engineers and Admins (FR-21). Customers see an incident under its service's public name, never its internal title, and it moves to resolved once a RESOLVED update is posted or the team resolves it (#160, 2026-11-11) | Shane | Recorded 2026-11-02 (#149) |
| D46 | How do live updates reach the browser? | Selected: first, the incident list and incident page refetch every few seconds and when the tab regains focus, and say so when the backend cannot be reached (NFR-14.2); then the backend pushes "incident changed" with server-sent events and the page refetches. No WebSocket, queue, or Redis while there is one backend instance. Before the second step, check that Vercel's `/api` rewrite keeps a stream open, and send the token with a fetch-based client, since `EventSource` cannot set headers | Shane | Recorded 2026-11-02 (HLD v4) |
| D47 | Which email provider sends notification emails? | Open: a free tier reached over SMTP or HTTP, with its key on Render only; also who gets an email (for example the assignee and each escalation level) | Shane | Decide before the email worker is built |
| D48 | Who can use Settle, and how do people sign in? | Selected: open sign-up. Anyone creates an org (a company, a team, or one person) and becomes its Admin; Admins add people, and invitations come later. Each org sees only its own data: `org_id` on every org-owned table, filtered by Hibernate's `@TenantId`. A membership holds each person's role; one org per person on screen for now. Sign in with email and password; Google sign-in next, GitHub later. Design: [plans/2026-11-02-organizations-design.md](../2-design/organizations-design.md) | Shane | Recorded 2026-11-02 |
| D49 | Where does the backend read an org's logs and metrics? | Selected: from the org's own Grafana. An Admin saves its Prometheus and Loki URLs, a read-only token (AES-GCM under `TELEMETRY_SECRET_KEY`, bound to the org, never returned or logged), and how its telemetry is named; each service can set its label value. No server-wide fallback: without a connection, logs and metrics answer 503. Org-supplied URLs must be https and public, checked when saved, before each call, and by the HTTP client's DNS resolver while connecting (DNS rebinding, Codex D03). `POST /webhooks/grafana` takes Grafana's own alert format | Shane | Recorded 2026-11-13 (#260) |

Before implementing an affected rule, its owner should record the team's decision, actual date, and Slack or issue reference. Optional features need not block EPIC 1. If a suggested review date does not fit the sprint, change it explicitly rather than presenting the review as completed.
