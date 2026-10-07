# Sprint Plan

**Group 2** | DevOps Incident Management System

## 1. Sprint Roadmap (tentative)

Each sprint delivers something that works end to end. Sprint length: 2 weeks, adjusted to course deadlines.

| Sprint | Focus | Epics | User stories |
|---|---|---|---|
| **Sprint 1** | Foundation: log in, register a service, create an incident | E1, E2 (part) | US-01, US-05, US-03 |
| Sprint 2 | Alerts and on-call | E1, E2 | US-02, US-04, US-06 |
| Sprint 3 | Incident response | E3 | US-07, US-08, US-09, US-10 |
| Sprint 4 | Tracking and records | E4 | US-11, US-12, US-17 |
| Sprint 5 | Resolution and insights | E5, E6 | US-13, US-14, US-15, US-16 |
| Sprint 6 | Extensions (if time allows) | E6, E7 | US-18, US-19, US-20 |

## 2. Sprint 1

### Goal

> A logged-in user can register a service and create an incident for it.

This is the smallest slice that touches every layer: frontend, backend, database, and CI.

### Stories

| Story | Description | Priority | Suggested team |
|---|---|---|---|
| US-01 | Manage users and roles | Must | Team 1 |
| US-05 | Register a service | Must | Team 2 |
| US-03 | Create incident manually | Must | Team 3 |

### Sprint 1 scope for each story

Only the parts needed for this sprint. The rest of each story's acceptance criteria come in later sprints.

**US-01: Users and roles**
- Login and logout
- Three roles: Admin, Responder, Viewer
- Admin can create users and assign a role
- Passwords stored as salted hashes
- Server rejects actions a role is not allowed to perform

**US-05: Register a service**
- Add and edit a service: name, owner, criticality
- Response and resolution targets per severity (SEV1 to SEV4)
- Default targets from the survey: SEV1 respond within 15 minutes, SEV4 respond within 1 day

**US-03: Create incident**
- Form with title, description, affected service, severity
- Missing fields are highlighted; nothing is saved
- Saved incident gets a unique ID, reporter, creation time, and status OPEN
- Simple incident list page showing ID, title, service, severity, and status

### Setup tasks (shared, first 2 to 3 days)

| Task | Notes |
|---|---|
| Create GitHub repo and branch rules | `main` protected; changes through pull requests only |
| Spring Boot app skeleton | One app with packages per module: `auth`, `catalog`, `incident` |
| React app skeleton | Login page and basic layout |
| Docker Compose | PostgreSQL only for now; RabbitMQ and Redis come later |
| Database migrations | One tool (e.g., Flyway) so everyone's schema stays the same |
| GitHub Actions CI | Build and run tests on every pull request |

### Dependencies

- **US-03 needs services (US-05) and logged-in users (US-01).** To avoid waiting, Team 3 starts with seed data: one test user and one test service inserted by a migration. They switch to the real features once those are merged.
- **All teams depend on the setup tasks.** Finish these first, together.

### Out of scope for Sprint 1

Alerts, on-call schedules, escalation, notifications, timeline, SLA clocks, RabbitMQ, Redis, AI. These come in later sprints.

### Definition of Done

A story is done only when:
1. Code is reviewed and merged into `main` through a pull request
2. Each acceptance criterion in scope has at least one automated test
3. CI passes
4. It works in the Docker Compose setup
5. It is shown in the sprint demo

### Sprint 1 demo

1. Admin logs in and creates a Responder user
2. Admin registers a service, for example "Payments," with targets per severity
3. Responder logs in and creates an incident for Payments as SEV2
4. The incident appears in the list with a unique ID and status OPEN
5. Responder tries to submit an incident with no title, and the form shows the error
6. Viewer logs in and cannot create an incident

### Risks

| Risk | Plan |
|---|---|
| Setup takes longer than expected | Timebox to 3 days; one person per task |
| Teams blocked waiting on each other | Use seed data; agree on table names on day 1 |
| Scope creep | Anything outside the three stories goes to the backlog, not this sprint |
