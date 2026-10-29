# Test Plan

How Settle is tested in Sprints 1 to 3, who does it, and when a change counts as tested. The test
cases are in [TEST_CASES.md](TEST_CASES.md), the results in [TEST_REPORT.md](TEST_REPORT.md), and
the requirement-to-test map in [TRACEABILITY.csv](../TRACEABILITY.csv).

## 1. Scope

**In scope:** the features of Sprints 1 to 3 in the
[implementation plan](../plans/2026-10-17-implementation-plan.md): users and roles, the service
catalog, incidents, SLA clocks, alert intake and deduplication, on-call, notifications, monitors and
heartbeats, acknowledgement, status and severity changes, escalation, reassignment and roles,
comments and corrections, and rate limits.

**Not in scope yet:** features of Sprints 4 to 6, the quality of AI answers (measured in the
[AI/ML roadmap](../plans/ai-ml-roadmap.md#data-and-evaluation)), and load testing, which runs in
Sprint 6.

## 2. Test levels

| Level | What it checks | Tools | Where it runs |
| --- | --- | --- | --- |
| Unit | Rules with no database: status transitions, SLA arithmetic, on-call slot choice, the 2-region verdict | JUnit 5, a controlled `Clock` | Every build |
| Integration | Saving and reading data, transactions and rollback, concurrency, migrations, append-only tables | Spring Boot tests with Testcontainers PostgreSQL 16 (`@IntegrationTest`) | Every build |
| API and security | Status codes, validation errors, and permissions for every endpoint and role | MockMvc, `SecurityMatrixTest` | Every build |
| Frontend component | Forms, field errors, role-based screens, the API client | Vitest, Testing Library, jsdom | Every build |
| AI service | Endpoints, contracts, and failure handling with fixed sample responses | pytest | Every build |
| Mutation | Whether the tests catch small bugs in the transition and SLA rules | PIT | Not in CI: on demand, and at the end of each sprint |
| Manual acceptance | The sprint demo, in Chrome and Firefox | People | End of each sprint |
| End to end | One complete incident flow in a browser | Cypress | Sprint 6 |
| Performance | NFR-01 response times and NFR-03 capacity on the deployed setup | k6 | Sprint 6 |

Each behaviour is tested at the one level that can verify it best; the same rule is not repeated
at three levels (see the [testing table in backend/AGENTS.md](../../backend/AGENTS.md#tests)).

## 3. Test design techniques

Every test case in [TEST_CASES.md](TEST_CASES.md) names the technique used to choose it.

| Technique | Used for |
| --- | --- |
| Equivalence partitioning | Valid and invalid inputs: login fields, roles, severities, criticality |
| Boundary value analysis | SLA deadlines, password length, on-call shift edges, failure thresholds, rate limits |
| Decision tables | Permissions (role × endpoint), alert deduplication, escalation eligibility |
| State transition testing | The incident lifecycle: every legal and illegal transition |
| Concurrency testing | Two acknowledgements at once, simultaneous alerts, simultaneous comments |
| Negative testing | Missing fields, bad tokens, stale versions, edits to append-only records |
| Statement and branch coverage (white box) | Measured on every build with JaCoCo (backend) and Vitest (frontend) |
| Basis path testing (white box) | The transition check and the SLA check: control-flow graph, cyclomatic complexity, one test per independent path |
| Mutation testing | PIT on the transition and SLA classes |

## 4. Environments and test data

| Environment | Used for |
| --- | --- |
| Local | Developers run `./mvnw verify` and `npm test`. Integration tests need Docker running |
| CI (GitHub Actions) | Every pull request and every push to `main`: all automated tests, coverage, and reports |
| Deployed (Vercel, Render, Supabase) | Manual acceptance at the end of each sprint; k6 in Sprint 6 |

- **Test data:** each test creates its own data and is rolled back afterwards. The opt-in dev seed
  (D27) is only for manual runs. No real personal data is used anywhere.
- **Time:** code that reads the time takes an injected `Clock`, so tests set the time exactly.

## 5. Entry and exit criteria

**A work item can start when** its issue has acceptance criteria, the API shape is posted, and the
issue lists the test case IDs it must automate.

**A pull request can merge when:**
1. The test cases listed in its issue are automated and pass in CI.
2. The test output is pasted into the PR.
3. New endpoints have their rows in `SecurityMatrixTest`.
4. Coverage of the changed files is shown in the PR comment. No minimum is set yet.

**A sprint is finished when:**
1. Every test case planned for the sprint passes, or has an open defect and a reason.
2. The sprint demo passed in Chrome and Firefox, with the versions recorded.
3. PIT has run on the rule classes and its score is recorded.
4. The sprint's section in [TEST_REPORT.md](TEST_REPORT.md) and the rows in
   [TRACEABILITY.csv](../TRACEABILITY.csv) are updated with real results only.

## 6. Responsibilities

| Who | Does |
| --- | --- |
| The author of each feature | Designs and automates the test cases for it, in the same PR |
| Shane (reviewer) | Checks in review that the listed test cases are covered and meaningful; runs PIT and the manual demo; writes the sprint's test report (#58) |
| Everyone | Reports bugs they find as GitHub issues with the bug template |

## 7. Coverage and mutation

- **Backend:** JaCoCo measures line and branch coverage on every build. The HTML report is saved as
  the `backend-coverage` artifact, and each PR gets a comment with overall and changed-file coverage.
- **Frontend:** Vitest measures coverage over every source file, including files no test imports.
  The report is saved as the `frontend-coverage` artifact.
- **Mutation:** PIT is not part of CI. Run it on demand, and at the end of each sprint on the
  transition and SLA classes. Its score shows
  whether the tests would catch a small bug, which coverage alone cannot.
- **Targets:** none are set yet. We report the real numbers each sprint.

## 8. Defects

- Every bug is a GitHub issue made with the **bug template**, labelled `bug`.
- The issue says what happened, what was expected, and how to reproduce it.
- The fixing PR links the issue and adds a test that fails without the fix.
- Each sprint's report lists the defects found and fixed.

## 9. Deliverables

| Deliverable | Where |
| --- | --- |
| Test plan | This file |
| Test cases | [TEST_CASES.md](TEST_CASES.md) |
| Test report per sprint | [TEST_REPORT.md](TEST_REPORT.md) |
| Requirement-to-test map | [TRACEABILITY.csv](../TRACEABILITY.csv) |
| Test, coverage, and mutation reports | CI artifacts: `backend-test-reports`, `backend-coverage`, `frontend-coverage`; PIT's HTML report |

## 10. Risks

| Risk | What we do |
| --- | --- |
| Three sprints in one week leave little time for tests | A PR without its listed tests is not merged |
| Integration tests need Docker, which not everyone runs | CI runs them on every PR; the PR shows the result |
| Concurrency tests can be flaky | Force the overlap: hold the first transaction open, and start the second only once it is blocked on a lock (as `OnCallServiceTest` does). Never rely on a fixed sleep |
| Tests that depend on the time | Inject `Clock`; never call `now()` directly |
| A test passes without checking anything | PIT's mutation score exposes it |
