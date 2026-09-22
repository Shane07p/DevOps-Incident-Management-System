# Group 2 — Project Guide

## What we are building

A web application that helps a team report an incident, assign a responder, track the response, and record the solution. The first goal is a complete working incident workflow that we can demonstrate and test.

This is a simplified implementation proposal. No stakeholder elicitation has been completed. The earlier requirements document remains separate; the reductions listed below need to be reflected there when the team agrees on scope. This ZIP contains design documents, not implemented software.

## Read these four documents and the test tracker

| File | Purpose |
| --- | --- |
| [PROJECT.md](PROJECT.md) | Scope, team, and working rules |
| [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md) | Repository layout, high-level design, and low-level design |
| [REQUIREMENTS_AND_STORIES.md](REQUIREMENTS_AND_STORIES.md) | Short requirements summary and starter user stories |
| [SPRINT_AND_TEST_PLAN.md](SPRINT_AND_TEST_PLAN.md) | Build order, essential tests, and course evidence |
| [TRACEABILITY.csv](TRACEABILITY.csv) | Existing FR sub-IDs mapped to specific planned checks and current status |

## First version

1. Login with Admin, Engineer, and Viewer roles.
2. Service catalog with an owner, SLA targets, fallback responder, and manually entered on-call time slots.
3. Manual incident creation and a basic authenticated alert endpoint.
4. Duplicate alert handling, assignment, in-app notifications, and escalation when nobody acknowledges.
5. Incident status changes, response and resolution timing, and a recorded timeline.
6. A dashboard, previous-incident search, resolution notes, and a simple postmortem with action items.

Use one incident workflow from creation to closure as the main demonstration. A test script can send alerts; we do not need to build a monitoring platform.

## Optional after the first version works

| Priority | Feature |
| --- | --- |
| Should | Local AI suggestions based on previous incidents and their fixes |
| Should | A basic health-check extension, if time permits |
| Could | One external notification channel or live board updates |

Choose an extension based on remaining time and course expectations. “Should” does not mean it must be finished before demonstrating the core.

Defer automatic on-call rotations, cross-service alert correlation, SLA pauses and business-hours calendars, full DORA reporting, public status pages, and automatic recovery processing. Automatic remediation is outside this version.

## What became simpler

- Four maintained documents and one CSV instead of 24 separate files.
- One application and one database; no message broker or distributed deployment.
- A few straightforward packages instead of a separate interface layer for every module.
- Assignment to an individual responder; team ownership stays in the service catalog.
- Explicit matching of alerts within one service; no inference that similar alerts have the same cause.
- SLA targets copied when an incident is created and kept fixed for that incident.
- In-app notifications first; no external delivery queue until an external channel is selected.
- Basic counts and incident search; no complete analytics suite.

These are proposed scope changes, not claims that the existing requirements or submitted work already say this.

## Stack

| Area | Choice |
| --- | --- |
| Backend | Java 21, Spring Boot 4.1, Spring Data JPA, Spring Security, Validation |
| Database | PostgreSQL 16, Flyway |
| Frontend | React, TypeScript, Vite, TanStack Query, Zod, Tailwind and shadcn/ui |
| Tests | JUnit, Mockito where useful, Testcontainers for database behavior, Vitest, Cypress |
| Quality and delivery | PIT for selected business rules; GitHub Actions; Docker Compose |
| Optional AI | Python, FastAPI, Ollama; LangGraph only when the selected workflow needs it |

Use Spring Boot 4.1 with Java 21 for the new project. Pin a stable 4.1 patch in the build file when scaffolding and use compatible dependencies. Follow Boot 4 documentation when older tutorials differ. Recharts is only needed if we add charts.

Version check, 22 September 2026: the [official project page](https://spring.io/projects/spring-boot) lists 4.1, and its [system requirements](https://docs.spring.io/spring-boot/system-requirements.html) include Java 21. Record any later version change here with its concrete reason; do not quietly fall back to 3.x.

## Team

| Student ID | Name | Sub-team |
| --- | --- | --- |
| 202401041 | Christian Shane Prashant | Team 1 |
| 202401051 | Diwan Shlok Dineshkumar | Team 1 |
| 202401063 | Gohel Himanshu Shaileshbhai | Team 1 |
| 202401029 | Bhatiya Dhruva Jiteshbhai | Team 2 |
| 202401026 | Bhakti Sudhir Patolia | Team 2 |
| 202401044 | Darji Utsav Girish | Team 2 |
| 202401021 | Bechara Utsav Bhaveshbhai | Team 3 |
| 202401014 | Manan Amreliya | Team 3 |
| 202401070 | Jada Manush Alpeshbhai | Team 3 |
| 202401033 | Bhungaliya Het Bhaveshbhai | Team 3 |

Every member should implement and test a meaningful part, make their own commits, and review another member's work. Use Slack for team communication and GitHub for issues, code, and reviews.

## Short decision log

Selected means a design choice adopted in this pack, not completed stakeholder validation. Open means the proposed default still needs a team decision. Owners and review dates below are suggested planning assignments, not agreed deadlines. Dates on selected rows record this document revision. Keep the old D identifiers so earlier discussions remain traceable.

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

Before implementing an affected rule, its owner should record the team's decision, actual date, and Slack or issue reference. Optional features need not block EPIC 1. If a suggested review date does not fit the sprint, change it explicitly rather than presenting the review as completed.
