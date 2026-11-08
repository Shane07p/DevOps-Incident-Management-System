# Implementation Plan: Sprints 1 to 4

**Planned for the week of 19 to 25 October 2026.** This plan replaces the Sprint 1 plan. It says
what each person builds, in what order, and how to check it. Find your name in
[Who does what](#who-does-what), then open your issue.

Sprint 1 finished on 26 October, 7 days late (its record is in the
[sprint plan](../sprint-plan.md#sprint-1-complete)). Sprints 2 to 4 are still in progress: each
work item below shows its issue and status, and GitHub's Sprint 2, 3, and 4 milestones are live.

| Sprint | Planned end | What the demo shows | Stories |
| --- | --- | --- | --- |
| 1 | Mon 19 Oct | A logged-in user registers a service and creates an incident. Every change is recorded | US-01, US-05, US-03 |
| 2 | Thu 22 Oct | An alert from a tool or a failing monitor creates or joins an incident. It is assigned to whoever is on call, who gets notified | US-02, US-04, US-06 |
| 3 | Sun 25 Oct | Responders acknowledge, change status and severity, reassign, and comment. Unacknowledged incidents escalate. Checks run from several regions | US-07 to US-11 |
| 4 | About 2 weeks; no date set yet | Responders see the service map and blast radius, runbooks, deploys, logs, and charts on an incident, and search past incidents by text and by meaning. Customers see the status page. An AI assistant reads incidents through MCP | US-12, US-15, US-17, US-19, US-20, US-25 to US-30 |

The sprints overlap. As soon as your PR for one sprint is in review, start your item for the next.

## Team

| Track | Members |
| --- | --- |
| Frontend | Shane, Shlok |
| Backend | Shane, Shlok, Utsav B, Utsav D, Manan, Het, Manush |
| AI/ML | Himanshu, Shlok, Bhakti, Dhruva |
| DevOps and Reviewer | Shane |

There is no testing track. Whoever builds a feature writes its tests in the same PR.

## Who does what

Items without an issue number get one before their sprint starts. Work was rebalanced on 2 Nov 2026 so every sprint ends by
Thu 12 Nov; each issue carries its own deadline at the top of its description.

| Person | Sprint 1 | Sprint 2 | Sprint 3 | Sprint 4 |
| --- | --- | --- | --- | --- |
| **Utsav B** | #47 Audit writer; #49 Service catalog API | #71 On-call schedules: assign at creation, fallback contact | #85 Escalation: policies, scheduler, outbox and RabbitMQ; #83 Acknowledge; #84 status and severity changes | #153 Audit log API; #154 Service map |
| **Utsav D** | #50 Incidents API (with Manan) | #70 Notifications: in-app rows and the bell API | #146 On-call changes audited; #147 Disable and enable members | — |
| **Manan** | #50 Incidents API (with Utsav D) | #72 Dedup and grouping: an alert creates or joins an incident | #87 Comments and corrections | #150 pgvector; #158 History search; #159 AI-1 in the backend |
| **Het** | #48 User admin API; creates `SecurityMatrixTest` | #68 Tool tokens, `POST /webhooks/alerts`; #69 rate limits | #86 Reassign and incident roles | #151 Personal access tokens; #152 Deploy tracking; #165 Access tokens page |
| **Manush** | — (the monitors work is #73) | #73 Monitors from one region: a failing monitor raises an alert; #74 Heartbeats; #79 monitors and heartbeats pages | #88 Grafana checks from 2 to 3 locations; Grafana alerts into intake | — (finishing #73, #74, #79, #88) |
| **Shlok** | #54 Services page; #55 Incident list, create form, and detail page | #80 On-call page; #81 alert inbox | #89 Incident actions; timeline with comments | #171 SLA clocks on the board; #198 Orgs and sign-up: frontend |
| **Shane** | #53 Users page; #59 Compose and settings; merge #61 | #77 Tools page; #78 notification bell; #75 Grafana Cloud account | #90 Escalation policies page; #82 RabbitMQ hosting; sprint records (#58) | #149 Sprint 4 stories, requirements, and decisions; #197 Orgs and sign-up: backend; #160 Public status page API; #155 Runbooks API; #166 Audit log page; #167 Service map page |
| **Himanshu** | Done (#21) | #62 Ollama embeddings; #76 demo shop v1 | #91 Agent tool loop on demo data | #162 MCP server with read tools; #163 Logs explorer and metrics dashboards; #168 History search page; #170 Similar incidents |
| **Bhakti** | — | #63 Postmortem dataset | #92 Log-pattern evidence | #161 AI-1 retrieval evaluation; #157 Evidence links; #164 Status page on real data; #169 Incident page: deploys, logs, evidence; #172 Runbooks pages |
| **Dhruva** | — | #64 Demo-shop scenario table | #142 Recorded demo-shop telemetry; #93 Metric-baseline and deploy-window evidence | #156 Logs and metrics API |

## Order of work

```
Sprint 1   #47 Audit ──► #48 Users · #49 Catalog · #50 Incidents ──► pages #53 #54 #55
Sprint 2   Het: webhook intake ──► Manan: dedup and grouping ──► Manush: monitors raise alerts
           Utsav B: on-call ──► incidents get an assignee      Utsav D: notifications ──► the bell
Sprint 3   Utsav D: acknowledge ──► Utsav B: escalation stops on acknowledgement
           Het: reassign and roles · Manan: comments (independent)
```

- **Het's webhook intake goes first in Sprint 2 (by Tue 20 Oct).** Dedup and monitors depend on it.
- **Frontend builds against the contract,** then connects when the backend PR merges.
- **Until on-call merges,** new incidents keep `assignee: null`. Nothing else waits for it.

## Decisions needed before coding

AGENTS.md: if a change depends on an Open decision, record the decision first. These are in the
[decision log](../PROJECT.md#short-decision-log); an Open one must be decided before the item it
blocks starts.

| Decision | Topic | Blocks | Status |
| --- | --- | --- | --- |
| D31 | One bearer token per tool, shown once, stored as a hash | #68 | Selected |
| D32 | Alerts arrive at `POST /webhooks/alerts`, outside JWT login | #68 | Selected |
| D33 | Notifications are rows written in the same transaction | #70 | Selected |
| D34 | Logs and metrics in Grafana Cloud | #75 | Selected |
| D35 | Probes are Grafana Cloud checks from 2–3 locations | #88 | Selected |
| D36 | Down when 2 locations fail twice in a row; Degraded when 2 are slow 3 times in a row | #88 | Selected |
| D38 | Personal access tokens (90 days) and read-only MCP tools | #151, #162 | Selected |
| D39 | Incident roles: Lead, Comms, Ops | #86 | Selected |
| D40 | What the demo shop simulates | #64, #76 | Selected |
| D44 | Incident embeddings in PostgreSQL with pgvector | #150, #159 | Selected |
| D45 | What the public status page shows; Lead, Comms, and Admins publish | #160, #164 | Selected |

**US-10:** a severity change keeps the SLA targets copied at creation (D09/D10, PROJECT_STRUCTURE
§8). The story says so too.

## Rules for this week

1. **One issue, one branch, one PR.** Open it as a draft early, so others can see it.
2. **Contract first.** Before coding an API, post its request and response shapes in your issue.
   The frontend builds against that.
3. **Tests in the same PR,** at the level in the [testing table](../../backend/AGENTS.md#tests). Your issue
   lists the [test case IDs](../testing/TEST_CASES.md) you must automate. Paste the test output in
   the PR.
4. **Security rows:** every new endpoint adds its rows to `SecurityMatrixTest` (Admin, Engineer,
   Viewer, no token).
5. **Migrations:** take the next free `V` number. If someone merges first, rename yours before
   merging. Never edit a migration that is already on `main`.
6. **Rebase on `main` every day.** Shane reviews the same day.
7. **Check your commit email** (`git config user.email`) is verified on your GitHub account, or your
   commits won't be credited to you.

---

## Work items

### Sprint 1

| Item | Owner | Issue | Tests | Status |
| --- | --- | --- | --- | --- |
| Foundations: resource server, `Clock`, error handler, `@IntegrationTest`, Swagger | Shane | #13 | | Done |
| Migrations V1 to V3, dev seed, `DatabaseSchemaTest` | Shlok | #18 | | Done |
| JWT login: `/api/auth/login`, `/me`, `/logout`, token-version check | Shane | #22 | `AuthControllerTest` | Done |
| `ai-service` skeleton: `/health`, stub `/suggest`, pytest, CI job | Himanshu | #21 | pytest | Done |
| Shared UI components | Manan, Het; fixes by Shane | #19, #39 | Vitest | Done |
| API client and Zod schemas | Bhakti | #41 | Vitest | Done |
| Frontend foundation, login page, API client fixes | Shane | #51, #52, #42 | Vitest | Done (PR #61) |
| Audit writer (`AuditService`) | Utsav B | #47 | `AuditServiceTest` | Done (PR #100) |
| User administration: create, list, change role; creates `SecurityMatrixTest` | Het | #48 | `UserAdminControllerTest`, `SecurityMatrixTest` | Done (PR #109) |
| `Severity` enum; service catalog with SLA targets and audit | Utsav B | #49 | `ServiceControllerTest` | Done (PR #102) |
| Incidents: create, list, detail; timeline and audit in one transaction | Utsav D, Manan | #50 | `IncidentTest`, `IncidentControllerTest`, `IncidentCreationRollbackTest` | Done (PR #103) |
| Users page | Shane | #53 | Vitest | Done (PR #105) |
| Services list and form with the SLA target grid | Shlok | #54 | Vitest | Done (PR #107) |
| Incident list, create form, and detail page | Shlok | #55 | Vitest | Done (PR #108) |
| Compose `web` service with nginx (D41); HikariCP settings (D43); `vite preview` proxy | Shane | #59 | Compose run | Done (PR #106) |
| Manual demo run, traceability, and the sprint record | Shane | #58 | | Sprint 1 done (PR #120); repeats every sprint |

- **#56** is folded into #48: Het creates `SecurityMatrixTest`, and every API PR adds its rows.
- **#57**, the Cypress demo test, moves to Sprint 6 with the other end-to-end work.
- **#43** has the first `AuditService`, catalog, and incident code. Utsav B splits it into #47 and
  #49; Utsav D and Manan reuse its incident code for #50.

### Sprint 2

| Item | Owner | Rules | Tests | Status |
| --- | --- | --- | --- | --- |
| Register tools: a token is shown once, stored as a hash, and can be revoked. `POST /webhooks/alerts` validates the token and payload. Rate limits on login and webhooks (D42, NFR-04.4) | Het | US-02, D31, D32 | Valid, invalid, and revoked tokens; missing fields create nothing; the token never appears in logs; over the limit returns 429 | #68, #69: Done (PRs #115, #126) |
| Dedup and grouping: `(source, event_id)` is unique; an explicit correlation key joins an active incident; otherwise a new incident through the normal creation flow | Manan | US-04, [§6](../PROJECT_STRUCTURE.md#6-create-and-group-incidents) | Testcontainers: a repeated event is ignored; two alerts with the same key at once make one incident; linking does not reset either clock | #72: Done (PR #123) |
| On-call schedules per service in the project time zone; a new incident is assigned to whoever is on call, or the fallback contact; the assignment goes on the timeline | Utsav B | US-06, FR-15, FR-05.1 | JUnit with a controlled `Clock` at shift boundaries; creation picks the right person or the fallback | #71: Done (PR #112) |
| Notifications: in-app rows written in the same transaction; list, unread count, mark as read | Utsav D | FR-07.1, D33 | A notification is saved with the change and rolls back with it; a user only sees their own | #70: Done (PR #148) |
| Monitors from one region (HTTP status) and heartbeats; a configured number of failures raises an alert; recovery updates the alert and adds a timeline entry, but never resolves the incident | Manush | FR-22, D29 | Controlled `Clock`: consecutive failures, a late heartbeat, recovery | #73, #74: Open |
| On-call page; alert inbox | Shlok | | Vitest | #80: Done (PR #124). #81: inbox merged (PR #125); open for the count and "routed to me", whose fields arrived with #123 |
| Tools page (token shown once); notification bell; monitors and heartbeats pages | Shane | | Vitest | #77: Done (PR #138). #78: Done (PR #176). #79: moved to Manush on 2 Nov; waits on #73 and #74 |
| Grafana Cloud account; its settings on Render | Shane | D34 | | #75: Open |
| Ollama embeddings | Himanshu | #62 | pytest | Done (PR #99) |
| Demo shop v1: payments, checkout, and search, sending logs and metrics | Himanshu | D34, D40 | | #76: Done (PR #111) |
| Postmortem dataset | Bhakti | #63 | pytest | Done (PR #110) |
| Demo-shop scenario table | Dhruva | #64 | pytest | Open |

**Cut first if late:** keyword and SSL monitor types.

### Sprint 3

| Item | Owner | Rules | Tests | Status |
| --- | --- | --- | --- | --- |
| Acknowledge: OPEN becomes ACKNOWLEDGED; the acknowledger becomes the assignee; the response clock stops; pending escalations are cancelled | Utsav B | US-08, [§7](../PROJECT_STRUCTURE.md#7-acknowledgement-and-escalation) | Testcontainers: two people acknowledge at once, one wins, the other gets 409 | #83: Open |
| Status changes (legal transitions only) and severity changes with a reason; SLA targets stay fixed | Utsav B | US-10, [§8](../PROJECT_STRUCTURE.md#8-status-and-sla-rules) | JUnit: every legal and illegal transition; a breach is over the target, not equal to it | #84: Open |
| Escalation: policies with levels and wait times; a scheduler re-checks that the incident is OPEN and unacknowledged, notifies the next level, and forces a version increment; the fallback is notified once; after a restart an overdue step runs once. Events go through the outbox to RabbitMQ | Utsav B | US-07, FR-07, D24 | Testcontainers: restart with an overdue escalation; escalation racing an acknowledgement | #85: Done (PR #135); its events reach RabbitMQ through the outbox (PR #177) |
| Reassign, and incident roles; reassigning never restarts a clock | Het | US-09, D39 | Every change is on the timeline; roles are checked on the server | #86: Done (PR #203; Lead made the assignee in the fixes PR) |
| Comments and corrections: separate timeline inserts with no incident version; allowed on CLOSED incidents; a correction links to the original, which stays | Manan | US-11, §7 | Two people comment at once and both are saved; an entry cannot be edited or deleted | #87: Open |
| Grafana Cloud checks from 2 to 3 locations; alert rules for Down and Degraded, and on the demo shop's error rate, post into intake | Manush | D35, D36 | One failing location raises nothing; two in a row do; two slow ones raise Degraded | #88: Open |
| Incident actions (acknowledge, status, severity, assign, roles); timeline with comments | Shlok | | Vitest | #89: Open |
| Escalation policies page; RabbitMQ hosting | Shane | | Vitest | #90: Done (PR #144). #82: Done (PR #177) |
| Agent tool loop on demo data | Himanshu | [AI/ML roadmap](ai-ml-roadmap.md) | pytest | #91: Done (PR #181) |
| Log-pattern evidence | Bhakti | AI/ML roadmap | pytest | #92: Done (PR #122) |
| Recorded demo-shop telemetry for the five scenarios | Dhruva | AI/ML roadmap | pytest | #142: Open |
| Metric-baseline and deploy-window evidence | Dhruva | AI/ML roadmap | pytest | #93: Open |

**Cut first if late:** the third check location. (RabbitMQ is in: the outbox relay publishes since #82.)

### Sprint 4

The stories are US-25 to US-30 in [user-stories.md](../user-stories.md), and the requirements FR-23 to
FR-28 in [REQUIREMENTS.md](../REQUIREMENTS.md). Each item's test cases are in
[TEST_CASES.md](../testing/TEST_CASES.md#sprint-4).

| Item | Owner | Rules | Tests | Status |
| --- | --- | --- | --- | --- |
| Sprint 4 stories, requirements, and decisions | Shane | D38, D44, D45 | | #149: Done |
| Orgs and sign-up: backend (merges with #198) | Shane | D48 | `OrgIsolationTest` | #197: Open |
| Orgs and sign-up: frontend | Shlok | D48 | Vitest | #198: Open |
| pgvector in every environment | Manan | D44 | `DatabaseSchemaTest` | #150: Open |
| Personal access tokens | Het | US-25, FR-23, D38 | TC-FR-23.1a to 23.4a | #151: Open |
| Deploy tracking | Het | US-19, FR-20.1 | TC-FR-20.1a to d | #152: Open |
| Audit log API | Utsav B | US-17, FR-13 | Its own cases (PR #179) | #153: In review (PR #179) |
| Service map: dependencies and blast radius | Utsav B | US-26, FR-14.2, FR-24 | Its own cases (PR #180) | #154: In review (PR #180) |
| Runbooks per service | Shane | US-27, FR-25 | TC-FR-25.1a to d | #155: In review |
| Logs and metrics API through the backend | Dhruva | US-28, US-29, FR-26, FR-27, D34 | TC-FR-26.1a to 27.1a | #156: Open |
| Evidence links on an incident | Bhakti | FR-09.3 | TC-FR-09.3a to c | #157: Open |
| History search | Manan | US-15, FR-17 | TC-FR-17.1a to d | #158: Open |
| AI-1 Find similar in the backend | Manan | US-18, FR-18, D44 | TC-FR-18.1a, 18.4a | #159: Open |
| Public status page and customer updates | Shane | US-20, FR-21, D45 | TC-FR-21.1a to 21.2a | #160: Open |
| AI-1 retrieval evaluation | Bhakti | AI/ML roadmap | pytest | #161: Done (PR #175) |
| MCP server with read tools | Himanshu | US-30, FR-28, D38 | TC-FR-28.1a to 28.2a | #162: Open |
| Logs explorer and metrics dashboards | Himanshu | US-28, US-29 | TC-FR-26.1b, 27.3a | #163: Open |
| Public status page on real data, and posting updates | Bhakti | US-20, D45 | TC-FR-21.1c | #164: Open |
| Access tokens page | Het | US-25 | TC-FR-23.1b | #165: Open |
| Audit log page | Shane | US-17 | TC-US-17a, b | #166: Open |
| Service map page | Shane | US-26 | TC-US-26a, b | #167: Open |
| History search page | Himanshu | US-15 | TC-FR-17.1e | #168: Open |
| Incident page: deploys, logs and charts, and evidence links | Bhakti | FR-09, FR-27.2 | TC-FR-27.2a | #169: Open |
| Similar incidents and the duplicate warning | Himanshu | US-18 | TC-FR-18.2a | #170: Open |
| SLA clocks and breaches on the board and incident page | Shlok | US-12, FR-16.3 | TC-FR-16.3e, f | #171: Open |
| Runbooks pages | Bhakti | US-27, FR-25.2 | TC-FR-25.2a | #172: Open |

**Cut first if late:** log patterns and saved views in the logs explorer; then the "by meaning" mode
of history search.

---

## API contract

### Permissions

Every endpoint on `main`, as `SecurityMatrixTest` checks it.

| Endpoint | Admin | Engineer | Viewer | No token |
| --- | --- | --- | --- | --- |
| `POST /api/auth/login` | ✓ | ✓ | ✓ | ✓ |
| `GET /api/auth/me`, `POST /api/auth/logout` | ✓ | ✓ | ✓ | 401 |
| `GET/POST /api/users`, `PUT /api/users/{id}/role` | ✓ | 403 | 403 | 401 |
| `GET /api/services`, `GET /api/services/{id}` | ✓ | ✓ | ✓ | 401 |
| `POST /api/services`, `PUT /api/services/{id}` | ✓ | 403 | 403 | 401 |
| `GET /api/incidents`, `GET /api/incidents/{id}` | ✓ | ✓ | ✓ | 401 |
| `POST /api/incidents` | ✓ | ✓ | 403 | 401 |
| `GET /api/alerts` | ✓ | ✓ | ✓ | 401 |
| `GET /api/notifications`, `POST /api/notifications/{id}/read` (your own only; another user's is 404) | ✓ | ✓ | ✓ | 401 |
| `GET /api/services/{id}/on-call`, `GET /api/services/{id}/escalation-policy` | ✓ | ✓ | ✓ | 401 |
| `PUT /api/services/{id}/on-call`, `PUT /api/services/{id}/escalation-policy` | ✓ | 403 | 403 | 401 |
| `GET/POST /api/tools`, `DELETE /api/tools/{id}` | ✓ | 403 | 403 | 401 |

`POST /webhooks/alerts` takes no login token: it needs a tool's bearer token, and answers 401 when that
is missing, unknown, or revoked (D31, D32).

### Request and response shapes

```jsonc
// POST /api/auth/login → 200
{ "username": "admin", "password": "…" }
{ "token": "eyJ…", "expiresAt": "…",
  "user": { "id": 1, "username": "admin", "displayName": "Dev Admin", "role": "ADMIN" } }

// POST /api/users → 201
{ "username": "asha", "displayName": "Asha", "password": "8 to 72 chars", "role": "ENGINEER" }

// PUT /api/users/{id}/role → 200
{ "role": "ADMIN" }

// POST /api/services → 201, PUT /api/services/{id} → 200
{ "name": "Payments", "description": "…", "ownerId": 1, "criticality": "CRITICAL",
  "targets": { "SEV1": { "responseMinutes": 15,   "resolutionMinutes": 240 },
               "SEV2": { "responseMinutes": 60,   "resolutionMinutes": 480 },
               "SEV3": { "responseMinutes": 240,  "resolutionMinutes": 2880 },
               "SEV4": { "responseMinutes": 1440, "resolutionMinutes": 10080 } } }
// response adds "id" and returns "owner": { "id", "displayName" } instead of "ownerId"

// POST /api/incidents → 201
{ "title": "Checkout failing", "description": "…", "serviceId": 1, "severity": "SEV2" }
// response, also GET /api/incidents/{id}:
{ "id": 7, "reference": "INC-7", "title": "…", "description": "…",
  "service": { "id": 1, "name": "Payments" }, "severity": "SEV2", "status": "OPEN",
  "reporter": { "id": 2, "displayName": "Dev Engineer" }, "assignee": null, "createdAt": "…",
  "responseTargetMinutes": 60, "resolutionTargetMinutes": 480, "responseDueAt": "…", "resolutionDueAt": "…",
  "timeline": [ { "id": 3, "eventType": "INCIDENT_CREATED", "actor": "Dev Engineer",
                  "details": "Reported as SEV2 on Payments", "recordedAt": "…" } ] }

// GET /api/incidents → 200, newest first
[ { "id": 7, "reference": "INC-7", "title": "…", "serviceName": "Payments",
    "severity": "SEV2", "status": "OPEN", "createdAt": "…" } ]

// POST /api/tools → 201; the token is shown this once and stored only as a hash (D31)
{ "name": "Grafana" }
{ "id": 3, "name": "Grafana", "token": "tt_…", "createdAt": "…" }
// GET /api/tools → 200: [ { "id": 3, "name": "Grafana", "createdAt": "…", "revokedAt": null } ]
// DELETE /api/tools/{id} → 204 (revokes the token)

// POST /webhooks/alerts with "Authorization: Bearer tt_…" → 202
{ "source": "grafana", "eventId": "evt-123", "service": "Payments", "title": "High error rate",
  "description": "…", "severity": "SEV2", "correlationKey": "pay-5xx", "details": { … } }
{ "status": "ACCEPTED", "eventId": "evt-123", "source": "grafana", "message": "Alert accepted for processing" }

// GET /api/alerts → 200, newest first. status: NEW (opened its incident) or ATTACHED (joined one);
// count: alerts grouped into the same incident
[ { "id": 9, "source": "grafana", "serviceName": "Payments", "incidentId": 7, "assigneeId": 2,
    "status": "ATTACHED", "correlationKey": "pay-5xx", "createdAt": "…", "count": 2 } ]

// GET /api/notifications → 200: your own, newest first
{ "notifications": [ { "id": 5, "incidentId": 7, "message": "You have been assigned to incident INC-7: …",
                       "createdAt": "…", "readAt": null } ],
  "unreadCount": 1 }
// POST /api/notifications/{id}/read → 200: that notification, with readAt set

// PUT /api/services/{id}/on-call → 200; replaces every shift
{ "fallbackContactId": 1, "shifts": [ { "responderId": 2, "startTime": "…", "endTime": "…" } ] }
// response, also GET:
{ "serviceId": 1, "fallbackContactId": 1, "fallbackContactUsername": "admin",
  "shifts": [ { "id": 11, "responderId": 2, "responderUsername": "engineer", "startTime": "…", "endTime": "…" } ] }

// PUT /api/services/{id}/escalation-policy → 200; replaces every level, numbered 1, 2, 3 in order
{ "steps": [ { "stepNumber": 1, "responderId": 2, "waitMinutes": 15 } ] }
// response, also GET (404 while the service has no policy):
{ "id": 7, "serviceId": 1, "updatedAt": "…",
  "steps": [ { "id": 21, "stepNumber": 1, "responderId": 2, "responderDisplayName": "Dev Engineer", "waitMinutes": 15 } ] }
```

Timestamps are ISO-8601 UTC.

### Errors

```json
{ "status": 400, "message": "Validation failed", "errors": { "title": "Title is required" } }
```

400 validation (`errors` names the field) · 401 no, expired, or revoked token, a wrong password, or a
missing or revoked tool token · 403 role not allowed · 404 unknown id · 409 duplicate username,
service name, or tool name, a repeated alert event ID with changed content, or a stale incident
version · 429 over a rate limit, with a `Retry-After` header.

### Sprint 2 and 3 endpoints still to build

Owners confirm the exact shapes in their issue before coding, then add them to the shapes above
when the PR merges. Status, assignment, and severity have separate endpoints so their rules stay
independent. The tools, webhook, alerts, notifications, on-call, and escalation-policy endpoints are built
(shapes above).

| Owner | Endpoints |
| --- | --- |
| Utsav D | `POST /api/incidents/{id}/acknowledge`, `POST /api/incidents/{id}/transitions`, `PUT /api/incidents/{id}/severity` |
| Het | `PUT /api/incidents/{id}/assignee`, `PUT /api/incidents/{id}/roles` |
| Manan | `POST /api/incidents/{id}/comments`, `POST /api/incidents/{id}/timeline/{entryId}/corrections` |
| Manush | `GET/POST /api/monitors`, `PUT /api/monitors/{id}`; a heartbeat ping URL per job; a check-results endpoint for probes |

---

## Database

Flyway migrations live in `backend/src/main/resources/db/migration`. A new migration takes the next
free number after the highest on `main` (backend/AGENTS.md).

| Migration | Tables |
| --- | --- |
| `V1__users_and_audit.sql` | `app_user` (id, username unique regardless of case, display_name, password_hash varchar(255), role, enabled, token_version, created_at) · `audit_entry` (id, actor_id nullable for system actions, action, item_type, item_id, details, recorded_at) |
| `V2__catalog.sql` | `service` (id, name unique, description, owner_id, criticality, created_at, updated_at) · `service_sla_target` (service_id, severity, response_minutes, resolution_minutes; PK service_id + severity; resolution ≥ response) |
| `V3__incidents_and_timeline.sql` | `incident` (id, title, description, service_id, severity, status, reporter_id nullable for system-created incidents, assignee_id nullable, created_at, response_target_minutes, resolution_target_minutes, version) · `timeline_entry` (id, incident_id, actor_id nullable for system actions, event_type, details, recorded_at, corrects_entry_id nullable, same incident only) |
| `V4__monitoring_tools.sql` | `monitoring_tool` (id, name unique regardless of case, token_hash unique, created_at, revoked_at) |
| `V5__service_name_case_insensitive.sql` | service names unique regardless of case |
| `V6__on_call_schedules.sql` | `service.fallback_contact_id` · `on_call_shift` (service_id, responder_id, start_time, end_time; end after start) |
| `V7__escalation_and_outbox.sql` | `escalation_policy` (one per service) and `escalation_step` · `escalation_task` (one per incident: current step, next due time, state) and `escalation_task_step` (the steps copied at creation) · `outbox` (events waiting for RabbitMQ) |
| `V8__alert_intake.sql` | `incident.correlation_source` and `correlation_key` · `alert` (source, event_id, service, correlation key, incident, payload and its fingerprint, status `NEW` or `ATTACHED`; `(source, event_id)` unique) |

- `audit_entry` and `timeline_entry` have triggers that reject `UPDATE`, `DELETE`, and `TRUNCATE`, so
  they are append-only.
- `action`, `item_type`, and `event_type` are upper-case codes such as `USER_CREATED`; the Java enums
  own the values.
- The table is `app_user` because `user` is reserved in PostgreSQL.
- Tables live in the `incident` schema (D26). Use unqualified names in migrations.
- Enums are `varchar` columns with a `CHECK` constraint. Timestamps are `timestamptz`.

**Dev seed** `db/devdata/R__dev_seed.sql` (opt-in, D27): users `admin`, `engineer`, and `viewer`,
all with password `DevPassw0rd!`, and the service `Payments` with the targets above. Enable it
locally with `FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/devdata`. Production and the test
suite run without it.

---

## Backend

Feature folders under `com.group2.incident` (`auth`, `audit`, `catalog`, `incident`, `alert`,
`notification`), each split into `controller`, `dto`, `entity`, `repository`, and `service`. See
the [layout](../../backend/AGENTS.md#layout). Controllers return DTO records, never entities.

- **Time:** inject the `Clock` bean into anything that reads the time.
- **Errors:** throw `NotFoundException`, `ConflictException`, `FieldValidationException`,
  `UnauthorizedException`, or `RateLimitExceededException`. No catch-all `Exception` handler: it
  hides Spring Security's 403.
- **Swagger:** `@Tag` on every controller and `@Operation(summary = "…")` on every endpoint. Swagger
  UI is at `/swagger-ui.html`.
- **Tests:** `@IntegrationTest` runs the whole app against Testcontainers PostgreSQL and rolls back
  after each test.
- **Login:** JWT (HS256) from `JWT_SECRET`, claims `sub` (user id) and `tv` (token version). Every
  request reloads the user and checks `tv` and `enabled`, so logout and role changes take effect at
  once.
- **Audit:** `AuditService.record(...)` uses `Propagation.MANDATORY`, so it commits or rolls back with
  the change it records.
- **Catalog:** all four severities need targets. Create and update write an audit entry; updates
  show before → after.
- **Incidents:** `IncidentService.create` is one `@Transactional` method: load the service, save the
  incident (OPEN, reporter from the token, targets copied), then the timeline entry and the audit
  entry. `IncidentCreationRollbackTest` makes `AuditService` throw and checks nothing was saved.
- **Sprints 2 and 3:** the rules are in [PROJECT_STRUCTURE](../PROJECT_STRUCTURE.md) §6 (alerts),
  §7 (acknowledgement and escalation), and §8 (status and SLA).

---

## Frontend

The layout is in the [frontend README](../../frontend/README.md): pages in `pages/`, the app shell in
`components/shell/`, Settle pieces in `components/app/`, and shadcn primitives in `components/ui/`.

- Use the design tokens from `index.css`, not raw colours.
- The API client adds the token, checks every response with Zod, and sends the user to `/login`
  on a 401.
- Hide what a role can't use: a Viewer sees no "Declare incident"; only an Admin sees Users and
  service editing. The server still checks.
- Every form input has an `id` equal to its field name.
- Use `import type` for type-only imports. No TypeScript `enum`. Zod 4 messages use `{ error: '…' }`.

---

## AI service

`ai-service/` is a separate Python app. Only the backend calls it (the MCP server is the one
exception, and it calls our REST API as the user). It never writes to the database, and the app
keeps working when it is down. It has `GET /health`, `GET /ready` (is Ollama reachable, with its
model pulled), `POST /embed` (an embedding from Ollama, #62), and `POST /suggest`, which is still a
stub that cites the past incidents it is given. Contract for `/suggest`:

```jsonc
// POST /suggest
{ "incident": { "title": "…", "description": "…", "service": "Payments", "severity": "SEV2" },
  "pastIncidents": [ { "id": 12, "title": "…", "description": "…", "fix": "…" } ] }
// → 200
{ "aiGenerated": true,
  "suggestions": [ { "step": "Check the payment gateway timeout", "basedOn": [12] } ] }
```

Every suggestion names the past incidents it came from (FR-18.2). The rest of the AI work is in the
[AI/ML roadmap](ai-ml-roadmap.md).

---

## Testing

The [test plan](../testing/TEST_PLAN.md) has the levels, techniques, and entry and exit criteria; the
[test cases](../testing/TEST_CASES.md) list every case for Sprints 1 to 3 with its owner; results go
in the [test report](../testing/TEST_REPORT.md).

- **Everyone tests their own work,** at the level in the [testing table](../../backend/AGENTS.md#tests).
- **Coverage:** JaCoCo (backend) and Vitest (frontend) run on every build, and each PR gets a
  coverage comment. PIT is not part of CI: run it on demand, and at the end of each sprint on the
  rule classes.
- **`SecurityMatrixTest`:** every endpoint, as Admin, Engineer, Viewer, and no token, asserting the
  exact status. Each API PR adds its rows.
- **Manual demo:** at the end of each sprint, run the demo in Chrome and Firefox and note the browser
  versions (#58).
- **Traceability:** put real test names in `TRACEABILITY.csv`. Mark a row Passed only after it has
  run, with the date and commit.
- **Cypress:** moves to Sprint 6.

---

## DevOps

Hosting is set up (D25): Vercel, Render, Supabase, and UptimeRobot.

- **#59:** a `web` service in `compose.yaml` built from `./frontend`, with nginx as the entry point
  (D41); HikariCP pool size and timeouts from environment variables (D43); a `preview` proxy for
  `/api` in `vite.config.ts`.
- **Sprint 2:** the Grafana Cloud account and its settings on Render (D34).
- **Sprint 3:** RabbitMQ hosting (CloudAMQP) for the outbox events, `RABBITMQ_URL` on Render (#82). Email
  and AI jobs consume them later (D24).
- A new setting is a new environment variable on Render, never a value in the repository.

---

## Definition of Done

1. The acceptance criteria in scope are met.
2. Your tests are in the PR and pass, with the output pasted.
3. CI passes, and Shane has merged the PR into `main`.
4. It works in the Docker Compose setup.
5. It is shown in the sprint demo.
