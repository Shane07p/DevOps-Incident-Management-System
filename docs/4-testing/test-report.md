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

## Sprint 2 (planned to end Thursday 22 October; in progress)

| Section | Result |
| --- | --- |
| Automated tests | |
| Coverage | |
| Mutation | |
| Test cases | |
| Manual demo | |
| Defects | |
| Notes | |

## Sprint 3 (planned to end Sunday 25 October; in progress)

| Section | Result |
| --- | --- |
| Automated tests | |
| Coverage | |
| Mutation | |
| Test cases | TC-D36a to f (#88), by hand on Grafana Cloud with the demo shop's fault switches, 13 November, `cec3c87`, checks from Singapore and Mumbai: b Pass (both failing for one check: Pending, nothing sent); c Pass (`bad_deploy`: Checkout is down fired after 2 failing checks, and Settle opened INC-1, SEV1); f Pass (still failing 8 minutes later: the same INC-1, each alert counted once); d Pass (`slow_gateway`: Payments is slow Pending after 2 slow checks, nothing sent); e Pass (3 slow checks: Settle opened INC-4, SEV2). Screenshots: [evidence/tc-d36](evidence/tc-d36/README.md). a (1 of 2 locations failing) was not run on the live stack, since a fault switch fails every location at once; it passed on 12 November in a local Grafana 12.2 with simulated check results, where one failing location left Search is down quiet. TC-D36g is automated |
| Manual demo | |
| Defects | |
| Notes | |

---

## Sprint 6 – Performance Testing with k6 (Issue #250)

Evaluated against non-functional performance requirements **NFR-01.1** (incident board and detail latency with 1,000 incidents) and **NFR-01.2** (incoming alert intake throughput and latency at 10 alerts/sec) using the k6 automated test suite in `perf/`.

- **Test Date:** 16 November 2026
- **Application Commit Under Test:** `d6e7f95`
- **Environment:** Local Docker Compose stack on Windows 11 / WSL2, Docker Desktop 4.96.0, Docker Engine 29.9.1, k6 v2.4.0 (commit/dd9395906d, linux/amd64), PostgreSQL 16.15 (pgvector 0.8.6), Spring Boot 4.1 backend (`incident-app`), Nginx 1.31.8 (`incident-web`).
- **Database Stored Incidents:** 1,000 valid synthetic incidents seeded via `perf/seed_1000_incidents.sql` with associated timeline entries. Incident references are assigned sequentially starting from `organization.next_incident_number` (e.g. `INC-<number>`) rather than assuming a fixed `INC-1` through `INC-1000` sequence, preserving organization numbering constraints and per-org uniqueness. The k6 test suite dynamically discovers the actual incident IDs via `GET /api/incidents` during test setup.

### Test Execution & Results Summary

| Benchmark | Target Requirement | Workload Profile | Target Rate / Concurrency | Metrics (p50 / p90 / p95 / Max) | Failed Rate | Threshold | Status |
|---|---|---|---|---|---|---|---|
| **Incident Board** (`GET /api/incidents`) | NFR-01.1 | 1,000 seeded incidents, 10 VUs (10s ramp, 60s steady, 10s ramp-down) | 10 concurrent VUs (6,764 total board requests) | p50: 24.65 ms<br>p90: 43.48 ms<br>p95: **52.89 ms**<br>Max: 173.85 ms | 0.00% (0 / 6,764) | p(95) < 2,000 ms<br>Failure rate < 1% | **Pass** |
| **Incident Detail** (`GET /api/incidents/{id}`) | NFR-01.1 | 1,000 seeded incidents, 10 VUs (10s ramp, 60s steady, 10s ramp-down) | 10 concurrent VUs (6,764 total detail requests) | p50: 7.58 ms<br>p90: 19.53 ms<br>p95: **25.03 ms**<br>Max: 112.06 ms | 0.00% (0 / 6,764) | p(95) < 2,000 ms<br>Failure rate < 1% | **Pass** |
| **Alert Webhook Intake** (`POST /webhooks/alerts`) | NFR-01.2 | Constant arrival rate for 30s | Target: 10 alerts/s<br>Achieved: **10.00 alerts/s** (301 iterations, 0 dropped) | p50: 11.83 ms<br>p90: 17.04 ms<br>p95: **20.39 ms**<br>Max: 64.00 ms | 0.00% (0 / 304) | p(95) < 1,000 ms<br>Failure rate < 1% | **Pass** |

> **Notes on Execution Results:** Both performance requirements comfortably satisfied their respective thresholds: incident board p95 was 52.89 ms (threshold 2,000 ms, 37x headroom), incident detail p95 was 25.03 ms (threshold 2,000 ms, 79x headroom), and alert intake p95 was 20.39 ms at 10 alerts/sec (threshold 1,000 ms, 49x headroom). Zero requests failed across 13,530 incident queries and 304 webhook requests. Execution instructions and reproducible runners are documented in [perf/README.md](../../perf/README.md).
