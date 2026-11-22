# Test Report

Real results only, one section per sprint, written at the end of the sprint (#58). The
[test plan](test-plan.md) says what is run; the [test cases](test-cases.md) say what each test checks.

Every number names where it came from: a CI run link, or a local run with its date and commit.

## How to fill in a sprint

| Section | What to record | Where to find it |
| --- | --- | --- |
| Automated tests | Tests run, passed, failed, and skipped for the backend, frontend, and AI service | The CI run on `main` at the end of the sprint |
| Coverage | Backend line and branch coverage (JaCoCo); frontend statement, branch, and line coverage (Vitest) | The `backend-coverage` and `frontend-coverage` artifacts |
| Mutation | PIT mutation score on the transition and SLA classes, and the surviving mutants worth fixing | `./mvnw test-compile org.pitest:pitest-maven:mutationCoverage`, then `target/pit-reports/` |
| Test cases | Cases planned for the sprint: passed, failed, or not run, with reasons | [test-cases.md](test-cases.md) and the test results |
| Manual demo | The steps, Pass or Fail per step, and the Chrome and Firefox versions | The demo run |
| Defects | Bugs found in the sprint, with their issue links and status | GitHub issues labelled `bug` |
| Notes | What the results show, and what to improve next sprint | |

---

## Baseline, 17 October 2026

Measured before the Sprint 1 APIs merged, so later sprints have a starting point.

| Measure | Result | Source |
| --- | --- | --- |
| Frontend coverage | Statements 62.31% (43/69), branches 65.90% (58/88), functions 77.77% (21/27), lines 65.15% (43/66) | Local run of `npm run test:coverage` on `main` (`c1e5576`) with the coverage settings from the `ci/coverage` branch |
| Backend coverage | Not measured yet; JaCoCo arrives with the `ci/coverage` PR | — |

## Sprint 1 (planned to end Monday 19 October; its last PR merged Monday 26 October)

Measured on `main` at `b3d7e64`, the first commit with every Sprint 1 issue merged. It also holds
the Sprint 2 work merged by then (#68, #71, #76), so the counts include those tests.

| Section | Result |
| --- | --- |
| Automated tests | Backend 231 run, 231 passed, 0 failed, 0 skipped (JUnit and Testcontainers). Frontend 43 passed in 7 files (Vitest). AI service 35 passed; demo shop 35 passed (pytest). Source: [CI run 40382679122](https://github.com/Shane07p/DevOps-Incident-Management-System/actions/runs/40382679122) on `b3d7e64`, 26 October |
| Coverage | Backend: lines 94.97% (623/656), branches 70.24% (118/168), instructions 93.68% (3426/3657). Frontend: statements 46.17% (567/1228), branches 34.56% (309/894), functions 43.62% (212/486), lines 48.03% (525/1093). Source: the `backend-coverage` and `frontend-coverage` artifacts of the same run |
| Mutation | PIT 1.30.0 on the SLA classes (`SlaCalculator`, `Incident`) with `SlaTest` and `IncidentTest`: 7 mutants, 5 killed, 0 survived, 2 not covered; test strength 100%. The 2 not covered: `SlaCalculator.dueAt`, which nothing calls (`Incident` computes the due times), and `Incident.getReference`, which only the integration tests check. The transition classes arrive in Sprint 3. Source: local run, 27 October, `b3d7e64` |
| Test cases | 54 planned for Sprint 1 (TC-FR-01.1a to l, TC-FR-01.2a to e, TC-US-01a to k, TC-FR-14.1a to i, TC-FR-02.1a to d, TC-FR-02.2a, b, TC-FR-04.1a, TC-FR-16.1a, TC-FR-16.3a to d, TC-FR-16.4a, TC-FR-08.3a, b, TC-FR-13a, b). 51 automated and passed. 2 checked only in the database, with no API test yet: TC-FR-14.1c, e. 1 with no test: TC-FR-14.1f (each criticality, then an unknown one) |
| Manual demo | 10 steps, all Pass in Chrome 155.0.8059.85 and in Firefox 153.0 (Playwright's build), 27 October, `b3d7e64`, on a fresh PostgreSQL 16 with only the bootstrap admin. The steps were driven by a Playwright script rather than by hand, with a screenshot per step. Steps: (1) Admin logs in and lands on Home; (2) adds an Engineer and (3) a Viewer on the Users page; (4) registers a service with an owner, criticality, and SLA targets; (5) logs out, and the old session no longer opens the app; (6) the Engineer declares a SEV2 incident on the service; (7) its page shows INC-n, OPEN, both due times, and "Reported as SEV2 on …" on the timeline; (8) it is first in the incident list; (9) the Viewer sees it but cannot declare one; (10) the Viewer cannot open the Users page |
| Defects | No issues labelled `bug`. Found in review and fixed before merge: an unknown service id returned 400 instead of 404 (#102); the rollback test passed whether or not creation rolled back (#103); the handler for an unreadable body checked a Jackson 2 type that Spring Boot 4 never throws (#103). Seen in the demo, not yet filed: a toast covers the right end of the dock, in both browsers |
| Notes | Sprint 1 closed 7 days late: several PRs needed two or three review rounds, and #103 had to be rebuilt on #102. Backend coverage is high, but frontend coverage fell from 62.31% (17 October baseline) to 46.17%, as the landing, incident, and service pages arrived with few tests. Next: API tests for TC-FR-14.1c, e, f; Vitest tests for the new pages; remove or use `SlaCalculator.dueAt` |

## Sprint 2 (planned to end Thursday 22 October; last PR merged 26 October)

Sprint 2 work was merged together with Sprint 1 backlog. The first commit on `main` that includes every Sprint 2 issue is `b3d7e64`, measured via [CI run 40382679122](https://github.com/Shane07p/DevOps-Incident-Management-System/actions/runs/40382679122) on 26 October 2026.

| Section | Result |
| --- | --- |
| Automated tests | Backend 231 run, 231 passed, 0 failed, 0 skipped (JUnit and Testcontainers). Frontend 43 passed in 7 files (Vitest). AI service 35 passed; demo shop 35 passed (pytest). Source: [CI run 40382679122](https://github.com/Shane07p/DevOps-Incident-Management-System/actions/runs/40382679122) on `b3d7e64`, 26 October |
| Coverage | Backend: lines 94.97% (623/656), branches 70.24% (118/168), instructions 93.68% (3426/3657). Frontend: statements 46.17% (567/1228), branches 34.56% (309/894), functions 43.62% (212/486), lines 48.03% (525/1093). Source: `backend-coverage` and `frontend-coverage` artifacts from the same CI run |
| Mutation | PIT 1.30.0 on SLA classes (`SlaCalculator`, `Incident`): 7 mutants, 5 killed, 0 survived, 2 not covered; test strength 100%. Not-covered: `SlaCalculator.dueAt` (never called) and `Incident.getReference` (integration-test only). Source: local run, 27 October, `b3d7e64` |
| Test cases | Same set as Sprint 1 — 54 planned, 51 automated and passed, 2 database-only (TC-FR-14.1c, e), 1 no test yet (TC-FR-14.1f) |
| Manual demo | 10 steps, all Pass in Chrome 155.0.8059.85 and Firefox 153.0, 27 October, `b3d7e64`. Driven by Playwright script with one screenshot per step. Steps: sign-in, add Engineer, add Viewer, register service with SLA, sign-out, declare SEV2 incident, verify incident page, verify incident list order, Viewer cannot declare, Viewer cannot open Users page |
| Defects | No `bug`-labelled issues. Pre-merge fixes: unknown service id returned 400 not 404 (#102); rollback test false-positive (#103); wrong Jackson 2 exception type (#103). Post-demo (not filed): toast covers dock right end |
| Notes | Sprint closed 7 days late; PRs required multiple review rounds. Frontend coverage dropped from 62.31% baseline to 46.17% as new pages arrived with few tests |

## Sprint 3 (planned to end Sunday 25 October; last PR merged 12 November)

Sprint 3 spanned a larger feature set. Final commit measured on `main` is `cec3c87` (13 November). Key PRs: incident actions (#231), SLA clocks (#232), public status page (#233), personal access tokens (#219), audit log (#221), service map (#222), runbooks (#220).

| Section | Result |
| --- | --- |
| Automated tests | Backend tests grew as new controllers were added. Frontend 42 test files, 386 tests passed (Vitest). AI service 174 passed, 1 warning; demo shop 95 passed, 2 warnings. Source: [CI run 43741333114](https://github.com/Shane07p/DevOps-Incident-Management-System/actions/runs/43741333114) on 15 November (closest main run to Sprint 3 completion) |
| Coverage | Backend: JaCoCo all coverage checks met (see CI artifact `backend-coverage`, run 43741333114). Frontend: statements 90.4%, branches 83.43%, functions 87.05%, lines 91.89%. Source: `frontend-coverage` artifact, run 43741333114, 15 November |
| Mutation | PIT mutation data not yet present in the Sprint 3 CI run (mutation testing introduced in Sprint 5 via PR #292). No mutation score recorded for Sprint 3 |
| Test cases | TC-D36a to f (#88): run by hand on Grafana Cloud with demo shop fault switches, 13 November, `cec3c87`, checks from Singapore and Mumbai — b, c, d, e, f all Pass. TC-D36a passed on 12 November locally in Grafana 12.2. TC-D36g automated. Screenshots: [evidence/tc-d36](evidence/tc-d36/README.md) |
| Manual demo | Not recorded for Sprint 3 |
| Defects | No `bug`-labelled issues filed during Sprint 3 |
| Notes | Sprint closed 18 days late due to scope growth. Frontend coverage improved significantly from 46% (Sprint 2) to 90%+ as Vitest tests were added for new pages |

---

## Sprint 4 (ended 12 November 2026)

Sprint 4 delivered the AI features (AI-1 similar incidents, AI-2 SRE investigation, AI-3 drafting), postmortems, heartbeats, deploy tracking, logs/metrics API, Grafana integration, and the frontend redesign foundation. Last PR merged: #264 (postmortems, 12 November). Measured on [CI run 43741333114](https://github.com/Shane07p/DevOps-Incident-Management-System/actions/runs/43741333114), 15 November, commit at end of sprint window.

| Section | Result |
| --- | --- |
| Automated tests | Backend 1,970 assertions across 95 test-class runs (summed from CI log), 0 failures, 0 skipped (JUnit and Testcontainers). Frontend 42 test files, 386 tests passed (Vitest). AI service 174 passed, 1 warning; demo shop 95 passed, 2 warnings. Source: [CI run 43741333114](https://github.com/Shane07p/DevOps-Incident-Management-System/actions/runs/43741333114), 15 November |
| Coverage | Backend: all JaCoCo coverage floors met (artifact `backend-coverage`, run 43741333114). Frontend: statements 90.4%, branches 83.43%, functions 87.05%, lines 91.89% (artifact `frontend-coverage`, run 43741333114). AI service and demo-shop coverage not yet enforced this sprint |
| Mutation | Mutation testing (PIT) not yet in CI for Sprint 4; introduced by PR #292 in Sprint 5 |
| Test cases | TC-D36a to f completed (see Sprint 3 section). AI evaluation test cases run as part of PR #285 (Sprint 5 carry-over) |
| Manual demo | Not recorded for Sprint 4 |
| Defects | #279: demo-shop OpenTelemetry vulnerability (GHSA-mf5v-w3w3-f6cg), fixed and merged same sprint. No open `bug`-labelled issues at sprint close |
| Notes | Largest sprint by PR count. Frontend test coverage held above 90% despite significant new page additions. Mutation testing scheduled for Sprint 5 |

## Sprint 5 (ended 16 November 2026)

Sprint 5 delivered CI hardening (secret scan, property-based tests, coverage floors, mutation testing — PR #292), k6 performance tests (#287), Cypress E2E (#288), accessibility pass (#294), AI evaluation runs (#285), and free-tier readiness (#289). Last PR merged: #295 (follow-ups page, 18 November). Measured on [CI run 44177000192](https://github.com/Shane07p/DevOps-Incident-Management-System/actions/runs/44177000192), 17 November.

| Section | Result |
| --- | --- |
| Automated tests | Backend 1,982 assertions across 97 test-class runs (summed from CI log), 0 failures, 0 skipped. Frontend 43 test files, 393 tests passed. AI service 174 passed, 1 warning; demo shop 95 passed, 2 warnings. Source: [CI run 44177000192](https://github.com/Shane07p/DevOps-Incident-Management-System/actions/runs/44177000192), 17 November |
| Coverage | Backend: all JaCoCo coverage floors met (artifact `backend-coverage`, run 44177000192). Frontend: statements 90.53%, branches 83.48%, functions 87.18%, lines 91.98% (artifact `frontend-coverage`, run 44177000192). AI service and demo-shop Python coverage enforcement introduced this sprint via PR #292 |
| Mutation | PIT 1.30.1 on transition and SLA classes: **28 mutations generated, 27 killed (96%)**, 1 survived. Source: CI run 44177000192, `mutation-report` artifact, 17 November |
| Test cases | Cypress E2E (TC-57): 1 spec, 1 test, 1 passed, 0 failed, 48 s — full incident flow from declaration through closure and postmortem. k6 NFR-01.1 and NFR-01.2: all local benchmarks passed (see k6 section below). Accessibility: 460 Vitest tests passed, 0 axe violations in Edge 158, all keyboard checks passed |
| Manual demo | Not recorded as a separate manual step; Cypress E2E covers the core incident workflow end-to-end |
| Defects | No `bug`-labelled issues at sprint close |
| Notes | Mutation score 96% (27/28). One surviving mutant in transition policy; logged for investigation. Secret scan clean. CI pipeline now enforces backend coverage floors, mutation score, and secret scan on every PR |

## Sprint 6 — Summary (started 16 November 2026)

Sprint 6 delivered frontend redesign (Parts 2 and 3), SSE live updates (#293), OAuth sign-in (#298), status pages (#300), follow-ups page (#295), and stabilisation work (#297). Measured on [CI run 44900237062](https://github.com/Shane07p/DevOps-Incident-Management-System/actions/runs/44900237062), 21 November (latest main run before stabilisation PR).

| Section | Result |
| --- | --- |
| Automated tests | Backend 2,538 assertions across 126 test-class runs (summed from CI log), 0 failures, 0 skipped. Frontend 61 test files, 572 tests passed. AI service: 258 passed, 1 warning (commit `bfa61e4`, see AI evaluation section below); demo shop 95 passed, 2 warnings. Cypress E2E 1 spec, 1 test, passed. Source: [CI run 44900237062](https://github.com/Shane07p/DevOps-Incident-Management-System/actions/runs/44900237062), 21 November |
| Coverage | Backend: all JaCoCo floors met (artifact `backend-coverage`, run 44900237062). Frontend: statements 94.1%, branches 90.56%, functions 90.18%, lines 94.86% (artifact `frontend-coverage`, run 44900237062). Python: ai-service ≥78% enforced by PR #301; demo-shop ≥90% enforced by PR #301 |
| Mutation | PIT 1.30.1: **28 mutations generated, 27 killed (96%)**, 1 survived (same surviving mutant as Sprint 5). Source: CI run 44900237062, `mutation-report` artifact, 21 November |
| Test cases | Cypress E2E (TC-57): passed. Performance NFR-01.1 and NFR-01.2: local benchmarks all passed (see k6 section). Accessibility: 0 axe violations in Edge 158 (see accessibility section). AI evaluation AI-2/AI-3/AI-4: scripted pipeline checks passed; no live Ollama run recorded |
| Manual demo | Deferred — deployed app manual testing for Part 2 of #297 blocked pending browser access to Vercel deployment |
| Defects | No `bug`-labelled issues filed during Sprint 6 as of 22 November |
| Notes | Frontend test coverage reached highest point: statements 94.1%, branches 90.56%. Backend test count grew from 1,982 (Sprint 5) to 2,538, reflecting SSE, OAuth, status page, and follow-up tests. Python coverage enforcement added to CI via PR #301 (#297 Part 1) |

---

## Sprint 6 – Performance Testing with k6 (Issue #250)

Evaluated against non-functional performance requirements **NFR-01.1** (incident board and detail latency with 1,000 incidents) and **NFR-01.2** (incoming alert intake throughput and latency at 10 alerts/sec) using the k6 automated test suite in `perf/`.

- **Test Date:** 16 November 2026
- **Application Commit Under Test:** `d6e7f95`
- **Environment:** Local Docker Compose stack on Windows 11 / WSL2, Docker Desktop 4.96.0, Docker Engine 29.9.1, k6 v2.4.0 (commit/dd9395906d, linux/amd64), PostgreSQL 16.15 (pgvector 0.8.6), Spring Boot 4.1 backend (`incident-app`), Nginx 1.31.8 (`incident-web`).
- **Database Stored Incidents:** 1,000 valid synthetic incidents seeded via `perf/seed_1000_incidents.sql` with associated timeline entries. Incident references are assigned sequentially starting from `organization.next_incident_number` (e.g. `INC-<number>`) rather than assuming a fixed `INC-1` through `INC-1000` sequence, preserving organization numbering constraints and per-org uniqueness. The k6 test suite dynamically discovers the actual incident IDs via `GET /api/incidents` during test setup.

### Local Benchmark Results

| Benchmark Run | Target Requirement | Workload Profile | Target Rate / Concurrency | Metrics (p50 / p90 / p95 / Max) | Failed Rate | Threshold | Status |
|---|---|---|---|---|---|---|---|
| **NFR-01.1 Original Benchmark: Incident Board** (`GET /api/incidents`) | NFR-01.1 | 1,000 seeded incidents, 10 VUs (10s ramp, 60s steady, 10s ramp-down) | 10 concurrent VUs (6,764 total board requests) | p50: 24.65 ms<br>p90: 43.48 ms<br>p95: **52.89 ms**<br>Max: 173.85 ms | 0.00% (0 / 6,764) | p(95) < 2,000 ms<br>Failure rate < 1% | **Pass** |
| **NFR-01.1 Original Benchmark: Incident Detail** (`GET /api/incidents/{id}`) | NFR-01.1 | 1,000 seeded incidents, 10 VUs (10s ramp, 60s steady, 10s ramp-down) | 10 concurrent VUs (6,764 total detail requests) | p50: 7.58 ms<br>p90: 19.53 ms<br>p95: **25.03 ms**<br>Max: 112.06 ms | 0.00% (0 / 6,764) | p(95) < 2,000 ms<br>Failure rate < 1% | **Pass** |
| **NFR-01.1 Additional Validation: Incident Board** (`GET /api/incidents`) | NFR-01.1 | Additional validation run on 1,330 stored incidents, 10 VUs (10s ramp, 60s steady, 10s ramp-down) | 10 concurrent VUs (5,810 completed iterations, 5,810 board requests) | p50: 35.01 ms<br>p90: 52.75 ms<br>p95: **60.14 ms**<br>Max: 193.13 ms | 0.00% (0 / 5,810) | p(95) < 2,000 ms<br>Failure rate < 1% | **Pass** |
| **NFR-01.1 Additional Validation: Incident Detail** (`GET /api/incidents/{id}`) | NFR-01.1 | Additional validation run on 1,330 stored incidents, 10 VUs (10s ramp, 60s steady, 10s ramp-down) | 10 concurrent VUs (5,810 completed iterations, 5,810 detail requests) | p50: 7.91 ms<br>p90: 16.63 ms<br>p95: **20.51 ms**<br>Max: 61.80 ms | 0.00% (0 / 5,810) | p(95) < 2,000 ms<br>Failure rate < 1% | **Pass** |
| **NFR-01.2 Grouping-Path Benchmark** (`POST /webhooks/alerts`) | NFR-01.2 | Constant arrival rate for 30s with reused correlation keys (exercises alert grouping into existing incidents) | Target: 10 alerts/s<br>Achieved: **10.00 alerts/s** (301 iterations, 0 dropped) | p50: 11.83 ms<br>p90: 17.04 ms<br>p95: **20.39 ms**<br>Max: 64.00 ms | 0.00% (0 / 304) | p(95) < 1,000 ms<br>Failure rate < 1% | **Pass** |
| **NFR-01.2 New-Incident-Path Benchmark** (`POST /webhooks/alerts`) | NFR-01.2 | Constant arrival rate for 30s with strictly unique correlation keys (exercises full incident creation path) | Target: 10 alerts/s<br>Achieved: **10.00 alerts/s** (301 iterations, 0 dropped) | p50: 24.30 ms<br>p90: 32.36 ms<br>p95: **37.88 ms**<br>Max: 69.73 ms | 0.00% (0 / 304) | p(95) < 1,000 ms<br>Failure rate < 1% | **Pass** |

> **Notes on Local Execution Results:**
> - **NFR-01.1:** The original benchmark met the threshold with 1,000 seeded incidents (Board p95: 52.89 ms; Detail p95: 25.03 ms). The subsequent validation run on an expanded dataset of 1,330 incidents confirmed sustained compliance (Board p95: 60.14 ms; Detail p95: 20.51 ms), well below the 2,000 ms ceiling.
> - **NFR-01.2:** Two distinct workloads were evaluated:
>   1. *Grouping-path benchmark:* Reused 4 correlation keys, resulting in 4 incidents created and 297 alerts attached (`status: ATTACHED`), measuring intake and grouping latency (p95: 20.39 ms).
>   2. *Full new-incident-path benchmark:* Generated a strictly unique correlation key per iteration, resulting in 301 distinct new incidents verified in the database (incident count increased from 1,029 to 1,330; 301 alerts with `status: NEW`, 0 with `ATTACHED`). This verified the slower creation path including SLA copying, organization row-locking for atomic sequence numbering, on-call assignment, notifications, and timeline logging (p95: 37.88 ms, 26x headroom below 1,000 ms).
> - Zero requests failed across all local runs (13,530 incident queries on original run, 11,620 on validation run, and 608 total webhook requests). Detailed execution instructions are in [perf/README.md](../../perf/README.md).

### Deployed low-load run (Render / Supabase)

Run on 18 November 2026 against `https://incident-backend-f340.onrender.com`, serving commit `2b2e2cf`
(read from `/actuator/info`), with k6 v2.1.0 from a laptop in India. The dev seed users exist only in local
databases, so the run used a throwaway org made through the public sign-up ("k6 perf check (deployed)"), with one
`Payments` service. The alert script registered its own tool and revoked it at the end. The alert script ran first
so the org had incidents for the board and detail run (`WORKLOAD_PROFILE=deployed`).

| Benchmark | Workload | Requests | Latency (min / median / p90 / p95 / max) | Failed | Threshold | Status |
|---|---|---|---|---|---|---|
| **NFR-01.2 Alert intake, new-incident path** (`POST /webhooks/alerts`) | 1 alert/s for 30 s, a new correlation key per alert | 29 alerts (2 dropped by k6's arrival scheduler), 29 incidents opened | 1.51 s / 2.29 s / 3.02 s / **3.12 s** / 3.16 s | 0% (0 / 32) | p(95) < 1,000 ms | **Failed** |
| **NFR-01.1 Incident board** (`GET /api/incidents`) | 2 VUs for 30 s | 46 | 330 ms / 471 ms / 643 ms / **709 ms** / 1.01 s | 0% | p(95) < 2,000 ms | **Passed** |
| **NFR-01.1 Incident detail** (`GET /api/incidents/{id}`) | 2 VUs for 30 s | 46 | 544 ms / 622 ms / 736 ms / **806 ms** / 925 ms | 0% | p(95) < 2,000 ms | **Passed** |

> **Notes on the deployed run:**
> - Every request succeeded and every check passed, but alert intake misses NFR-01.2 on the deployed setup: each
>   alert that opens an incident took 1.5 to 3.2 s, against a p95 of 37.88 ms (max 69.73 ms) locally on the same path.
> - From this laptop a request with no database work (`/actuator/info`) takes about 0.24 to 0.39 s, and
>   `/actuator/health` (one database check) about 0.41 to 0.47 s. Opening an incident makes many database calls one
>   after another (SLA copy, org lock and number, on-call lookup, timeline entries, notification, audit, escalation
>   task), and each one crosses from Render to Supabase, so the likely cause is that per-query latency on the free
>   tier, not the code path itself. Grouping into an open incident (fewer writes) was not measured on the deployed app.
> - The incident board and detail pass NFR-01.1 with the network round trip included, but this org held 29
>   incidents, not 1,000: the seed is for local databases only.
> - To rerun: sign up a throwaway org on the deployed app, add a service named `Payments`, then
>   `k6 run perf/nfr-01-2-alerts.js -e BASE_URL=… -e WORKLOAD_PROFILE=deployed -e ADMIN_EMAIL=… -e ADMIN_PASSWORD=…`
>   and the same for `perf/nfr-01-1-incidents.js`. Credentials come from the environment, never from the repository.

---

## Sprint 6 – Accessibility Pass (Issue #251)

Tests and browser audit run on 2026-11-20, source commit `b68579ce07c615c51c903cef41be16224515347b`.

| Section | Result |
| --- | --- |
| Automated tests | Full frontend suite: `npm test -- --coverage --maxWorkers=2`, 52 files passed, 460 tests passed, 312.80 s. Coverage: statements 90.96%, branches 83.47%, functions 87.87%, lines 92.22%; all thresholds passed. The Home focus check covers all 27 jsdom controls in forward and reverse order, including four main-page controls. |
| Axe audit (jsdom) | The Vitest scans cover serious and critical structural violations. jsdom cannot check rendered colour contrast, so `color-contrast` is disabled here; contrast evidence must come from the Edge browser audit. |
| Browser contrast audit | Edge 158.0.4370.33 with axe-core 4.14.1: Home `/home`, Incidents `/incidents`, Incident `/incidents/7`, Services `/services`, and Public status `/status/acme` each have 0 contrast violations in light and dark themes (10 scans; 0 incomplete checks). Contrast is measured in the browser, not jsdom. [Raw measurements and context](evidence/a11y/edge-axe-2026-11-20.json); [audit notes](evidence/a11y/README.md). |
| Colours | Light primary/focus ring `#a4532a` → `#934823`: active sidebar labels 4.0:1 → 4.77:1. Light resolved/closed text `#4e7946` → `#426b3b`: public-status subtitle 4.21:1 → 5.14:1. Dark primary `#ee936b` and resolved text `#a5c79b` stay at 6.81:1 and 9.09:1 respectively. All four are above the normal-text minimum of 4.5:1. |
| Browser keyboard checks | All 26 visible desktop Home controls (including four main-page controls) passed forward and reverse Tab order, visibility, focus, and rendered outline/box-shadow checks. jsdom has 27 stops because it does not apply the CSS hiding the mobile menu button on desktop. The phone navigation drawer passed all 17 stops, forward/reverse wraparound, and focus restoration. Change status trapped Tab and restored focus on Escape after both keyboard and mouse opening. |
| Notes | These results use synthetic incident/service fixtures, included in the JSON; they are not production or recorded incident data. The browser audit measures the colour-contrast rule only; structural violations are covered by Vitest/axe. |

---

## Sprint 6 — AI Evaluation Runs (Issue #252)

Evaluated on 2026-11-20, commit `bfa61e4` (branch `feat/ai-evaluation-runs`), across features AI-2 (SRE scenario investigation), AI-3 (drafting verification), and AI-4 (severity triage hint).
Configured model: `qwen2.5:0.5b` (configuration only; see each section's provider).
No live Ollama result is recorded in this report.

Live-run status: **No**. The configured Ollama endpoint was unreachable; no inference with `OllamaProvider` or `qwen2.5:0.5b` was performed. The AI-2 and AI-3 numbers below are scripted pipeline checks.

### AI-2: AI SRE Scenario Investigation

Evaluation across the 5 demo fault scenarios (`SCN-01` to `SCN-05`) from `ai-service/data/scenarios.yaml` and their recorded fixtures in `ai-service/data/fixtures/scenarios/`.
All results and timings in this section come from scripted ScenarioEvaluatorProvider.

- **Scripted cause-match check:** 5/5 on 5 demo scenarios (100.0%)
- **End-to-end investigation duration (scripted ScenarioEvaluatorProvider):** mean 0.5 ms (min: 0.3 ms, max: 0.6 ms). The timer wraps the complete agent loop, not a streaming first-hypothesis event.

| Scenario | Service | Fault Switch | Cause Matches Scenario Label | Generated Top-1 Hypothesis | Time (ms) | Status |
|---|---|---|---|---|---|---|
| `SCN-01` | payments | `slow_gateway` | **Passed** | Payment gateway latency degradation. | 0.6 | `completed` |
| `SCN-02` | checkout | `error_spike` | **Passed** | Database transaction deadlocks are causing checkout failures. | 0.3 | `completed` |
| `SCN-03` | checkout | `bad_deploy` | **Passed** | A recent checkout deployment introduced a regression. | 0.5 | `completed` |
| `SCN-04` | order-fulfillment | `queue_backlog` | **Passed** | The order fulfillment worker is stalled or paused. | 0.3 | `completed` |
| `SCN-05` | payments | `expiring_certificate` | **Passed** | Payment gateway TLS certificate expiry. | 0.5 | `completed` |

ScenarioEvaluatorProvider reads the expected hypotheses from scenarios.yaml. These are scripted pipeline regression checks, not measurements of model accuracy or inference latency.

### AI-3: Drafting Verification Checklist

Evaluation across all writing kinds (`SUMMARY`, `STATUS_UPDATE`, `POSTMORTEM`) using scripted DraftingAdversarialProvider with reference sanitization and schema verification. All results and timings below come from that provider.
Input is a synthetic INC-42 incident with timeline entries 1 and 2, an unconfirmed hypothesis, a synthetic follow-up record, and an accepted AI suggestion.

- **Checklist Pass Rate:** 3/3 (100.0%)
- **Fabricated Reference Leakage Rate:** 0.0%
- **Draft pipeline duration (scripted DraftingAdversarialProvider):** mean 0.2 ms (min: 0.1 ms, max: 0.4 ms)
- Reference leakage is checked after production sanitization. It is not a measurement of raw model hallucinations or model accuracy.

| Draft Kind | Cites Real Incidents/Entries | Invents No IDs | Valid Structure | Latency (ms) | Checklist Result |
|---|---|---|---|---|---|
| `SUMMARY` | Yes | Yes (0 leaked) | Yes | 0.4 | **Passed** |
| `STATUS_UPDATE` | Yes | Yes (0 leaked) | Yes | 0.1 | **Passed** |
| `POSTMORTEM` | Yes | Yes (0 leaked) | Yes | 0.1 | **Passed** |

### AI-4: Triage Severity Classification

Evaluated against the 25 hand-labelled cases in `ai-service/data/triage_eval_set.jsonl` using PR #283's `POST /triage` endpoint (called by `TriageHintService` in the backend).
Every count and percentage below comes from the real rule-based _classify_severity code in main.py through that endpoint; no live model or scripted provider is involved.

- **Overall Triage Accuracy:** 11/25 (44.0%)

| Severity Level | Labelled Cases | Correctly Classified | Accuracy |
|---|---|---|---|
| **SEV1** | 6 | 3 | 50.0% |
| **SEV2** | 7 | 2 | 28.6% |
| **SEV3** | 6 | 5 | 83.3% |
| **SEV4** | 6 | 1 | 16.7% |

### Notes

- Evaluation runner: `ai-service/ai_evaluation.py`
- Automated unit tests: `ai-service/tests/test_ai_evaluation.py`
- AI-4 scores PR #283's `POST /triage` endpoint (`TriageHintService` backend integration) against `ai-service/data/triage_eval_set.jsonl`.
- Each AI-2/AI-3 section identifies its actual provider. Scripted checks exercise the pipeline and must not be presented as live model results.

### Reproduction and verification

- Evaluation code: [bfa61e4](https://github.com/Shane07p/DevOps-Incident-Management-System/commit/bfa61e4ed9aa33be4ef3e3a070e9977afc44c1cc).
- Scripted run: `cd ai-service && python ai_evaluation.py --use-mock --model qwen2.5:0.5b --markdown`.
- Recorded logs, baseline/fault metric values, and deploys are adapted into evidence bundles; incident/timeline context is synthetic. The metric values are repackaged into baseline/fault points.
- The triage requests use empty similar-incident and service lists; this run measures severity classification, not service routing.
- Full AI-service suite on `bfa61e4`: `258 passed, 1 warning in 20.15s`. The warning is a Starlette/httpx dependency deprecation.
- `python scripts/check_docs.py`: `Docs check passed.`

---

## Sprint 6 — Cypress incident flow (Issue #57)

Run on 2026-11-20, source commit `07a877c91522100530954f0d59e830211574f4d2`.
The test used a fresh synthetic organisation against the Compose `app` profile, with a real
PostgreSQL database, backend, and nginx frontend. The isolated local project used ports 18080/18081;
CI uses the same stack and Cypress command on 8080/8081.

```text
npx cypress run --config baseUrl=http://127.0.0.1:18081

Spec: incident-flow.cy.ts
Specs: 1 passed / 1 total
Tests: 1
Passing: 1
Failing: 0
Pending: 0
Skipped: 0
Duration: 48 seconds
All specs passed!
```

The scenario waits for each member dialog to disappear and asserts names in the members table.
It clicks the Radix checkbox and verifies `aria-checked`, checks Viewer restrictions while the
incident is OPEN, and then verifies that the Engineer can acknowledge the same incident.
Status options are selected by keyboard; request and dialog completion are awaited without fixed sleeps.
After closure, an Engineer posts a comment and the Viewer reads it without a comment form/button.
The public update, postmortem review, follow-up, Insights values, and declare-form validation also pass.
AI investigation is omitted under #57's 15 November cutoff exception; the API-based postmortem step
is retained from the approved earlier review.

Other checks: 460 frontend unit tests passed in 52 files (224.78 s, commit `892e5f3`);
Cypress TypeScript checking and frontend lint passed. The Compose frontend build passed.
The UI image skips downloading an unused Cypress binary; the separate local/CI runner executes Cypress.

---

## PR #295 — service deploy compatibility and org follow-ups

On 2026-11-21, source commit `968f092` passed all 62 tests in the five affected page suites:
`FollowUpsPage`, `ServicesPage`, `EscalationPage`, `StatusPagesPage`, and `NotBuiltYetPage`.
Command: `npm test -- FollowUpsPage.test.tsx ServicesPage.test.tsx EscalationPage.test.tsx StatusPagesPage.test.tsx NotBuiltYetPage.test.tsx --maxWorkers=2` (32.42 s).
The three missing-version read regressions failed before the compatibility fix; they pass with it.
These tests use synthetic responses from the existing API contracts, not production recordings.

The follow-ups checks cover due-date order, incident links, kinds, owners, server-reported overdue
state, URL-backed owner/status/overdue filters, numeric owner deep links, read-only Viewers, Admin
and Engineer status-only updates with refetch, failed updates, both empty states, and retry after
a list failure. Service saves omit an absent version but retain zero and a nonzero loaded version.
Backend validation of missing or stale versions is unchanged.

Edge 158 browser checks ran on `703efa3`, whose production frontend is unchanged in `968f092`.
All twelve combinations passed: light/dark at 1440/375 px, for Admin and Viewer with ordinary
fixtures, plus Admin with long synthetic titles and owner names. Document widths exactly matched
the viewports (1440 or 375 px); phone tables scrolled inside their panels (341 px viewport,
900 px normal content or 1027 px long content). Fonts loaded, no page exceptions occurred, and
axe found zero WCAG A/AA violations within the main content in every checked view. A phone
interaction check verified Mine/Overdue URL filters, a status-only save, list refetch, filtered
empty state, and Clear filters. Screenshots and the JSON audit are local under
`codex-work/reviews/d47-followups-browser/`, outside this worktree; no screenshots are committed.

`python scripts/check_docs.py`: `Docs check passed.`
