# Testing and Workflow

The build order, epics, sprint scope, and team assignments live in [sprint-plan.md](sprint-plan.md) and [epics-and-conflicts.md](epics-and-conflicts.md). This file covers how the work is tested, tracked, and reviewed.

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
