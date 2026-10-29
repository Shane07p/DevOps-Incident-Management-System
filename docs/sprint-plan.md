# Sprint Plan

**Group 2** | DevOps Incident Management System

## 1. Sprint roadmap

Each sprint delivers working features end to end: API, UI, tests, and docs. Sprints 1 to 3 were
planned for the week of 19 to 25 October (decided 17 October). Sprint 1 finished on 26 October;
Sprints 2 and 3 are still in progress. Later sprints are about 2 weeks. The features are described in [FEATURES.md](FEATURES.md), and the AI work in the
[AI/ML roadmap](plans/ai-ml-roadmap.md). If a sprint runs late, the items in the last column go
first.

| Sprint | Must ship | Stories | Cut first if late |
|---|---|---|---|
| **1** | Users and roles, service catalog with SLA targets, incidents (create, list, detail). Frontend foundation, then the login, shell, home, incidents, services, and users screens | US-01, US-05, US-03 | Home widgets beyond open incidents |
| 2 | Alert intake and deduplication, tool tokens, on-call, notifications. Uptime monitors from one region, heartbeats. Demo shop v1 sending logs and metrics to Grafana Cloud | US-02, US-04, US-06 | Keyword and SSL monitor types |
| 3 | Acknowledge, status and severity changes, assignment, incident roles, escalation (outbox and RabbitMQ), comments and corrections. Checks from several regions. Grafana alerts into our intake | US-07, US-08, US-09, US-10, US-11 | A third probe region |
| 4 | Logs explorer, metrics dashboards, service map (links entered by hand), runbooks, deploy tracking, public status page, history search. AI-1 Find similar; MCP read tools | US-12, US-15, US-17, US-19, US-20 | Log patterns and saved views |
| 5 | Postmortems, follow-ups, insights. AI-2 AI SRE, AI-3 Drafting, AI-4 Triage hint; MCP write tools | US-13, US-14, US-16, US-18 | Service map links derived from traces |
| 6 | Hardening: the Cypress end-to-end test, accessibility, performance, the demo script, AI evaluation runs, and buffer time | — | — |

The monitoring, telemetry, service map, runbook, and MCP features still need user stories and
requirements. Write them before the sprint that builds them.

## 2. Current sprint

**Sprints 2 and 3.** Their goals, owners, order of work, API contract, and work items, each with
its issue and status, are in the [implementation plan](plans/2026-10-17-implementation-plan.md).
GitHub's Sprint 2 and Sprint 3 milestones show what is still open. Later sprints get their own plan
in [plans/](plans/).

## 3. Sprint records

Write one record at the end of each sprint, using real PR links and test results only.

| Field | Content |
|---|---|
| Goal and dates | |
| Stories and owners | |
| Completed work | Links to merged PRs and the demo |
| Verification | Checks run and their results |
| Remaining work and blockers | |
| Review feedback and next improvement | |

### Sprint 1 (complete)

| Field | Content |
|---|---|
| Goal and dates | A logged-in user can register a service and create an incident. Started 8 October 2026; planned to end Monday 19 October; the last Sprint 1 PR merged Monday 26 October, 7 days late |
| Stories and owners | US-01 Users and roles (Het, Utsav B, Shane), US-05 Register a service (Utsav B, Shlok), US-03 Create incident (Manan, Utsav D, Shlok) |
| Completed work | Foundations: backend #13, own schema #14, migrations and dev seed #18, JWT login #22, AI service skeleton #21, shared UI components #19 and #39, API client #41, backend layout and Lombok #44. Sprint 1 issues: frontend foundation, login page, and API client fixes #51, #52, #42 (PR #61); audit writer #47 (PR #100); user administration API and `SecurityMatrixTest` #48, #56 (PR #109); users page #53 (PR #105); service catalog API #49 (PR #102); services page #54 (PR #107); incidents API #50 (PR #103); incident pages #55 (PR #108); Compose web service with nginx, and HikariCP settings #59 (PR #106) |
| Verification | CI on `main` at `b3d7e64`: backend 231 tests, frontend 43, AI service 35, demo shop 35, all passing. Backend line coverage 94.97%, frontend statements 46.17%. The 10-step demo passed in Chrome 155 and Firefox 153. 51 of the 54 Sprint 1 test cases automated and passing. Details in the [test report](testing/TEST_REPORT.md#sprint-1-planned-to-end-monday-19-october-its-last-pr-merged-monday-26-october) |
| Remaining work and blockers | No API test yet for TC-FR-14.1c, e, f. Frontend coverage fell from 62% to 46%. The Cypress demo test is #57, in Sprint 6 |
| Review feedback and next improvement | Several PRs arrived off-plan or did not compile (#27, #43), and some commits were credited to the wrong author. Late in the sprint, PRs needed two or three review rounds: tests that could not fail (#103), duplicated catalog and audit classes (#103, #112), and Lombok and error-shape rules from AGENTS.md (#102). Next: one issue per task with acceptance criteria, test output in every PR, and a rebase on `main` before review |
