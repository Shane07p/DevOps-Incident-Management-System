# Testing and Workflow

Other documents cover the rest:
- the roadmap and sprint records: [sprint-plan.md](sprint-plan.md);
- each sprint's work: [plans/](plans/);
- the team: [PROJECT.md](PROJECT.md#team);
- the epics: [epics-and-conflicts.md](epics-and-conflicts.md).

This file covers how the work is tested, tracked, and reviewed. The detailed testing documents are
in [testing/](testing/):
- [TEST_PLAN.md](testing/TEST_PLAN.md): levels, techniques, environments, entry and exit criteria;
- [TEST_CASES.md](testing/TEST_CASES.md): every designed test case, its technique, and its test;
- [TEST_REPORT.md](testing/TEST_REPORT.md): real results per sprint.

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
| Roles, and missing or revoked tool tokens | API security tests |
| Complete incident flow | One focused Cypress end-to-end scenario |
| Important form behavior | Vitest where it catches a meaningful UI error |
| Transition and SLA rules | PIT mutation testing on the selected Java classes (not in CI; on demand and at the end of each sprint) |
| Optional AI failure | Stub timeout and invalid output; core remains usable |

Do not duplicate every rule across unit, integration, and browser tests. Use each test level for the behavior it can verify best. Record actual results; this document does not claim tests have run.

## Requirement-to-test tracking

[TRACEABILITY.csv](TRACEABILITY.csv) covers every FR sub-ID from the existing detailed requirements. Each row has its original statement, a specific planned check ID and expected result, and a status. Check IDs are planned labels, not claims that test files exist. The extra `ACK-OWNER` and `COMMENT-CONCURRENT` checks above cover the clarified design rules.

Use **Planned - not run**, **Optional - not selected**, **Deferred**, or **Scope review - not run** now. Scope-review rows identify differences or missing detail between the current requirements and the submitted PDF; they must not be counted as fully covered. When implementation starts, link the planned label to the real test method or manual evidence. Change status to Passed or Failed only after running that check, recording the run date and commit or evidence link. When only part of a requirement is built, use **Partly passed** with the same date and commit, and name what is still missing. Deferred items stay visible instead of disappearing from coverage. This CSV covers functional requirements; use the essential-tests table and detailed NFR/DR IDs for the additional quality and domain checks.

## Development workflow

1. **One task, one GitHub issue.** It lists the acceptance criteria, the API contract it follows,
   and one assignee, and is small enough for one or two days.
2. **Build it on a short-lived branch.** Check that `git config user.email` is verified on your
   GitHub account, so the commits count for you.
3. **Run `./mvnw verify` or `npm test` before opening the PR,** and paste the result into the PR
   description. A PR that doesn't build is sent back without review.
4. **Shane reviews the behaviour and the code.**
5. **Merge when the checks pass,** and show the result at the sprint review.

CI should build the backend and frontend and run the tests already configured. Add database integration tests when persistence behavior is introduced. Run targeted mutation tests at a planned checkpoint or on demand. Do not require a real local model in CI.

## Quality checks moved out of the NFRs

These were NFR sub-requirements, but they describe how we test rather than the system itself.

| Was | Check |
| --- | --- |
| NFR-03.2 | Load-test concurrent users, stored incident volume, and alert rate on the deployed setup (k6) against NFR-01 and NFR-03 |
| NFR-06.3 | Review incident creation and acknowledgement with users during testing; record what they found |
| NFR-09.2 | Record the Chrome and Firefox versions each manual run used |
| NFR-13.2 | Automated tests cover the main paths and important failures; AI tests use fixed sample responses in CI |
| NFR-13.3 | Run PIT mutation testing on selected core rules; no target score is claimed |

## Course work still required

These documents support the project but don't replace the assignment evidence.

- Maintain the stakeholder list and justify the elicitation technique selected for each stakeholder.
- **Elicitation so far:** one survey, with 1–5 respondents per role-specific question, mostly
  students (epics §2).
- **Planned next:** interviews, and an online forum post for on-call engineers.
- Record each technique's real status (Done, In progress, Not started) and its findings. Don't
  guess the status from this guide.
- Apply the techniques, record actual findings, and identify and resolve conflicting requirements.
- Keep functional, non-functional, and domain requirements updated as decisions change.
- Develop front-of-card stories and back-of-card acceptance criteria, group them into EPICs, and select sprint work.
- For Lab 6, submit the required hand-drawn activity diagram for one substantial functionality as a clear photo or scan. A generated architecture diagram does not replace it.
- Prepare the concept poster and other checkpoint material requested by the course.
- Keep team communication on Slack and development history on GitHub with individual attribution.

The existing stakeholder list can remain even when features are deferred. A stakeholder's concern is useful input; it does not automatically require a separate screen or module in the first version.

## Done means

The sprint record format is in [sprint-plan.md](sprint-plan.md#3-sprint-records).

Before marking a story done:
- check its acceptance criteria;
- review its code;
- verify the important failure case;
- update the affected documentation.

Keep the evidence short and real.
