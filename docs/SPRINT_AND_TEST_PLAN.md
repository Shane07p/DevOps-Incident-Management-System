# Sprint and Test Plan

## Build in small working steps

Choose sprint dates and capacity with the team. The table gives a build order, not a promise that each row fits in one sprint.

| Order / EPIC | Working outcome |
| --- | --- |
| 1. Foundation | Repository, database, basic CI, login, service setup, and seed data |
| 2. Incident response | Manual creation → assignment → notification → acknowledgement → timeline |
| 3. Alert intake and escalation | Authenticated alerts, deduplication, explicit grouping, persisted escalation |
| 4. Resolution and learning | Remaining lifecycle, SLA indicators, postmortems, action items, search, and dashboard |
| 5. Selected extension | One small optional feature after the core is stable |

Sprint 1 should aim for a small demonstrable path. Reduce selected stories if setup takes longer than expected. Avoid building all database tables and screens before one complete path works.

## Suggested team ownership

| Team | Starting area |
| --- | --- |
| Team 1: Shane, Shlok, Himanshu | Incident lifecycle, SLA, and escalation |
| Team 2: Dhruva, Bhakti, Utsav Darji | Login, services, on-call configuration, and alert intake |
| Team 3: Utsav Bechara, Manan, Manush, Het | Board and incident screens, history/postmortems, and CI coordination |

These are starting areas, not strict silos. Agree on request/response examples early so frontend and backend work can proceed together. Every member should contribute code, verification, documentation where relevant, and their own Git commits.

## Essential tests

| Behavior | Suitable check |
| --- | --- |
| Allowed and rejected status changes | JUnit unit tests |
| Response versus resolution clocks, exact deadline, fixed targets | Unit tests using a controlled clock |
| Alert retries and simultaneous matching alerts | PostgreSQL integration tests with Testcontainers |
| Two people acknowledging at once | Database integration test; only one accepted initial acknowledgement |
| Acknowledgement near an escalation deadline | Database integration test; no escalation based on stale state |
| A or B acknowledges after B is notified | `ACK-OWNER`: first accepted acknowledger becomes assignee; escalation alone leaves ownership unchanged |
| Two users comment while the incident changes | `COMMENT-CONCURRENT`: both entries survive; comment inserts neither require nor increment Incident version |
| Restart with an overdue escalation | Integration or documented restart test |
| Rejected transaction | Check that incident, timeline, and notification changes roll back together |
| Roles and invalid alert signatures | API security tests |
| Complete incident flow | One focused Cypress end-to-end scenario |
| Important form behavior | Vitest where it catches a meaningful UI error |
| Transition and SLA rules | PIT mutation testing on the selected Java classes |
| Optional AI failure | Stub timeout and invalid output; core remains usable |

Do not duplicate every rule across unit, integration, and browser tests. Use each test level for the behavior it can verify best. Record actual results; this document does not claim tests have run.

## Requirement-to-test tracking

[TRACEABILITY.csv](TRACEABILITY.csv) covers every FR sub-ID from the existing detailed requirements. Each row has its original statement, a specific planned check ID and expected result, and a status. Check IDs are planned labels, not claims that test files exist. The extra `ACK-OWNER` and `COMMENT-CONCURRENT` checks above cover the clarified design rules.

Use **Planned - not run**, **Optional - not selected**, **Deferred**, or **Scope review - not run** now. Scope-review rows identify differences or missing detail in the shortened pack; they must not be counted as fully covered. When implementation starts, link the planned label to the real test method or manual evidence. Change status to Passed or Failed only after running that check, recording the run date and commit or evidence link. Deferred items stay visible instead of disappearing from coverage. This CSV covers functional requirements; use the essential-tests table and detailed NFR/DR IDs for the additional quality and domain checks.

## Simple development workflow

1. Put the selected story in a GitHub issue with acceptance criteria.
2. Implement it on a short-lived branch and keep commits attributable to their authors.
3. Run relevant tests and open a focused pull request.
4. Have another member review the behavior and code.
5. Merge when required checks pass and demonstrate the result during sprint review.

CI should build the backend and frontend and run the tests already configured. Add database integration tests when persistence behavior is introduced. Run targeted mutation tests at a planned checkpoint or on demand. Do not require a real local model in CI.

## EPIC 1: first implementation tasks

1. Generate the Java 21 / Boot 4.1 Maven application with Web, JPA, Security, Validation, PostgreSQL, and Flyway.
2. Create the React/TypeScript app and a minimal login screen.
3. Add the first migrations and local seed users, one service, and its required settings.
4. Implement login and an authorized service read; verify that a Viewer cannot edit configuration.
5. Add CI that builds both applications and runs the tests that exist. Then begin the manual-incident story.

These are tasks to start, not completed work or executable scaffold files included in this ZIP.

## Course work still required

The design pack supports the project but does not replace the assignment evidence.

- Maintain the stakeholder list and justify the elicitation technique selected for each stakeholder.
- No elicitation is completed yet. For each stakeholder, record the actual status as **In progress** or **Not yet started**, then update it when completed. Do not guess the status from this guide.
- Apply the techniques, record actual findings, and identify and resolve conflicting requirements.
- Keep functional, non-functional, and domain requirements updated as decisions change.
- Develop front-of-card stories and back-of-card acceptance criteria, group them into EPICs, and select sprint work.
- For Lab 6, submit the required hand-drawn activity diagram for one substantial functionality as a clear photo or scan. A generated architecture diagram does not replace it.
- Prepare the concept poster and other checkpoint material requested by the course.
- Keep team communication on Slack and development history on GitHub with individual attribution.

The existing stakeholder list can remain even when features are deferred. A stakeholder's concern is useful input; it does not automatically require a separate screen or module in the first version.

## Short sprint record

Use this format in each sprint note:

- **Goal and dates:**
- **Selected stories and owners:**
- **Completed work:** links to actual PRs and demonstrations.
- **Verification:** checks run and their results.
- **Remaining work and blockers:**
- **Review feedback and next improvement:**

Before marking a story done, check its acceptance criteria, review its code, verify the important failure case, and update affected documentation. Keep the evidence short and real.
