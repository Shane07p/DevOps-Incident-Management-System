# Test Cases

Designed test cases for Sprints 1 to 3. The [test plan](TEST_PLAN.md) explains the levels and
techniques; results go in [TEST_REPORT.md](TEST_REPORT.md).

**IDs** extend the planned checks in [TRACEABILITY.csv](../TRACEABILITY.csv): `TC-FR-16.3b` is the
second designed case for check `TC-FR-16.3`. Features with no FR yet use their decision ID
(`TC-D36a`).

**Status** is one of:
- **Automated:** the test is on `main`.
- **In review:** the test is in an open PR.
- **Planned:** not written yet; the owner and issue are given.

A case is marked Passed or Failed only in [TEST_REPORT.md](TEST_REPORT.md), after a recorded run.

**How to use this file:** your issue lists the case IDs you must automate. Name the test so it is
easy to find, put its name in the Test column, and change the status in the same PR.

---

## Sprint 1

### Login and sessions (FR-01.1)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-01.1a | Equivalence (valid) | Correct username and password | 200 with a token, its expiry, and the user | `AuthControllerTest.loginReturnsTokenAndUser` | Automated |
| TC-FR-01.1b | Equivalence (invalid) | Correct username, wrong password | 401 "Invalid username or password" | `AuthControllerTest.wrongPasswordReturns401` | Automated |
| TC-FR-01.1c | Equivalence (invalid) | Unknown username | The same 401 as a wrong password, so usernames can't be guessed | `AuthControllerTest.unknownUserGetsTheSame401AsAWrongPassword` | Automated |
| TC-FR-01.1d | Equivalence (invalid) | Blank username and password | 400 naming each field | `AuthControllerTest.blankFieldsReturn400NamingEachField` | Automated |
| TC-FR-01.1e | Equivalence | Username in different letter case | Logs in; usernames ignore case | `AuthControllerTest.usernameIsCaseInsensitive` | Automated |
| TC-FR-01.1f | Equivalence (invalid) | A disabled user logs in | 401 | `AuthControllerTest.disabledUserCannotLogIn` | Automated |
| TC-FR-01.1g | State transition | Log out, then reuse the old token | 401 | `AuthControllerTest.logoutMakesTheTokenStopWorking` | Automated |
| TC-FR-01.1h | State transition | Disable a user who holds a valid token | Their next request gets 401 | `AuthControllerTest.tokenStopsWorkingWhenTheUserIsDisabled` | Automated |
| TC-FR-01.1i | Boundary value | Token used after its one-hour expiry | 401 | `AuthControllerTest.tokenExpiresAfterOneHour`, `expiredTokenReturns401` | Automated |
| TC-FR-01.1j | Negative | `GET /api/auth/me` with no token | 401 | `AuthControllerTest.meWithoutTokenReturns401` | Automated |
| TC-FR-01.1k | Equivalence | The login form submitted empty | Field errors shown; no request sent | `LoginPage.test.tsx` | In review (#61) |
| TC-FR-01.1l | Equivalence | The server answers 401 to the login form | "Invalid username or password" shown | `LoginPage.test.tsx` | In review (#61) |

### Permissions (FR-01.2): decision table

One row per endpoint; each cell is the expected status. `SecurityMatrixTest` runs every cell.

| Endpoint | Admin | Engineer | Viewer | No token |
| --- | --- | --- | --- | --- |
| `POST /api/auth/login` | 200 | 200 | 200 | 200 |
| `GET /api/auth/me`, `POST /api/auth/logout` | 200 | 200 | 200 | 401 |
| `GET/POST /api/users`, `PUT /api/users/{id}/role` | 2xx | 403 | 403 | 401 |
| `GET /api/services`, `GET /api/services/{id}` | 200 | 200 | 200 | 401 |
| `POST /api/services`, `PUT /api/services/{id}` | 2xx | 403 | 403 | 401 |
| `GET /api/incidents`, `GET /api/incidents/{id}` | 200 | 200 | 200 | 401 |
| `POST /api/incidents` | 201 | 201 | 403 | 401 |

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-01.2a | Decision table | Every cell above | The status in the cell | `SecurityMatrixTest` | Planned (Het, #48) |
| TC-FR-01.2b | Decision table | Admin-only paths as Engineer and Viewer | 403 | `AuthControllerTest.adminOnlyPathsRefuseOtherRoles` | Automated |
| TC-FR-01.2c | Decision table | Viewer creates an incident | 403 | `AuthControllerTest.viewerCannotCreateIncidents` | Automated |
| TC-FR-01.2d | Equivalence | Health check and API docs with no token | 200 | `AuthControllerTest.healthAndApiDocsNeedNoToken` | Automated |

### User administration (US-01)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-US-01a | Boundary value | Password of 7 characters | 400 naming `password` | `UserAdminControllerTest` | Planned (Het, #48) |
| TC-US-01b | Boundary value | Password of 8 characters | 201 | `UserAdminControllerTest` | Planned (Het, #48) |
| TC-US-01c | Boundary value | Password of 72 characters | 201 | `UserAdminControllerTest` | Planned (Het, #48) |
| TC-US-01d | Boundary value | Password of 73 characters | 400 | `UserAdminControllerTest` | Planned (Het, #48) |
| TC-US-01e | Equivalence (invalid) | Username that differs from an existing one only in case | 409 | `UserAdminControllerTest` | Planned (Het, #48) |
| TC-US-01f | Equivalence | Role `ADMIN`, `ENGINEER`, `VIEWER`; then an unknown role | 201 for each valid role; 400 for the unknown one | `UserAdminControllerTest` | Planned (Het, #48) |
| TC-US-01g | State transition | Change a user's role, then reuse their old token | 401; their new login carries the new role | `UserAdminControllerTest` | Planned (Het, #48) |
| TC-US-01h | Negative | Creating a user and changing a role | Each writes one audit entry; the password never appears in it or in logs | `UserAdminControllerTest` | Planned (Het, #48) |

### Service catalog (FR-14, US-05)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-14.1a | Equivalence | Valid service with targets for SEV1 to SEV4 | 201; the service can be chosen for incidents | `ServiceControllerTest` | Planned (Utsav B, #49) |
| TC-FR-14.1b | Equivalence (invalid) | Targets missing for one severity | 400 naming the severity | `ServiceControllerTest` | Planned (Utsav B, #49) |
| TC-FR-14.1c | Boundary value | Resolution target equal to the response target | Accepted | `DatabaseSchemaTest.slaTargetResolutionMayEqualResponse`; `ServiceControllerTest` | Automated (database); Planned (API) |
| TC-FR-14.1d | Boundary value | Resolution target one minute below the response target | 400 | `DatabaseSchemaTest.slaTargetResolutionBelowResponseIsRejected`; `ServiceControllerTest` | Automated (database); Planned (API) |
| TC-FR-14.1e | Boundary value | Response target of 0, then 1 minute | 0 rejected; 1 accepted | `DatabaseSchemaTest.slaTargetRejectsZeroResponse`; `ServiceControllerTest` | Automated (database); Planned (API) |
| TC-FR-14.1f | Equivalence | Criticality `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`; then an unknown value | Valid values accepted; unknown gives 400 | `ServiceControllerTest` | Planned (Utsav B, #49) |
| TC-FR-14.1g | Equivalence (invalid) | A second service with the same name | 409 | `DatabaseSchemaTest.duplicateServiceNameIsRejected`; `ServiceControllerTest` | Automated (database); Planned (API) |
| TC-FR-14.1h | Negative | Update a service's targets | One audit entry showing before and after | `ServiceControllerTest` | Planned (Utsav B, #49) |
| TC-FR-14.1i | Equivalence | The services form with a resolution target below its response target | A field error; nothing sent | Vitest | Planned (Shlok, #54) |

### Incidents (FR-02, FR-04.1)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-02.1a | Equivalence | Title, description, service, and severity all given | 201 with status OPEN | `IncidentControllerTest` | Planned (Utsav D, Manan, #50) |
| TC-FR-02.1b | Equivalence (invalid) | Each required field left out, one at a time | 400 naming that field; nothing saved | `IncidentControllerTest` | Planned (#50) |
| TC-FR-02.1c | Negative | The audit write fails during creation | No incident and no timeline entry saved | `IncidentCreationRollbackTest` | Planned (#50) |
| TC-FR-02.2a | Equivalence | Two incidents created one after the other | Different IDs and `INC-` references; reporter and time from the request | `IncidentControllerTest` | Planned (#50) |
| TC-FR-02.2b | Equivalence | List incidents | Newest first | `IncidentControllerTest` | Planned (#50) |
| TC-FR-04.1a | Equivalence | Severity `SEV1` to `SEV4`; then `SEV5` and blank | Valid ones accepted; others 400 | `IncidentControllerTest` | Planned (#50) |
| TC-FR-16.3a | Equivalence | Create an incident, then change the service's targets | The incident keeps the targets copied at creation | `IncidentTest` | Planned (#50) |
| TC-FR-02.1d | Equivalence | The incident form submitted with missing fields | Field errors shown; nothing sent | Vitest | Planned (Shlok, #55) |
| TC-FR-01.2e | Equivalence | A Viewer opens the app | No "Declare incident" button; `/incidents/new` refused | Vitest | Planned (Shlok, #55) |

### SLA clocks (FR-16): boundary values with a controlled clock

Target T is the copied target in minutes. Exceeding T is a breach; reaching it exactly is not.

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-16.3b | Boundary value | Elapsed time T − 1 minute | Not breached | `SlaTest` | Planned (#50) |
| TC-FR-16.3c | Boundary value | Elapsed time exactly T | Not breached | `SlaTest` | Planned (#50) |
| TC-FR-16.3d | Boundary value | Elapsed time T + 1 second | Breached | `SlaTest` | Planned (#50) |
| TC-FR-16.4a | Equivalence | An incident open across a night and a weekend | All elapsed time counts | `SlaTest` | Planned (#50) |
| TC-FR-16.1a | Equivalence | A new incident | `responseDueAt` and `resolutionDueAt` are creation time plus each target | `IncidentTest` | Planned (#50) |

### Append-only records (FR-08, FR-13)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-08.3a | Negative | Update or delete a timeline entry directly in the database | Rejected | `DatabaseSchemaTest.timelineEntryCannotBeUpdated`, `timelineEntryCannotBeDeleted` | Automated |
| TC-FR-08.3b | Negative | Truncate the timeline or the audit log | Rejected | `DatabaseSchemaTest.timelineCannotBeTruncated`, `auditLogCannotBeTruncated` | Automated |
| TC-FR-13a | Negative | Update or delete an audit entry | Rejected | `DatabaseSchemaTest.auditEntryCannotBeUpdated`, `auditEntryCannotBeDeleted` | Automated |
| TC-FR-13b | Integration | `AuditService.record` inside a transaction that rolls back | The audit entry is rolled back too; outside a transaction it throws | `AuditServiceTest` | In review (#47) |

---

## Sprint 2

### Tools and alert intake (FR-03.1, US-02, D31, D32)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-03.1a | Equivalence | Register a tool | The token is shown once; later reads never return it; only its hash is stored | `ToolControllerTest` | Planned (Het) |
| TC-FR-03.1b | Equivalence | An alert with a valid token and all required fields | Accepted | `AlertWebhookTest` | Planned (Het) |
| TC-FR-03.1c | Equivalence (invalid) | Missing token, wrong token, revoked token | 401; nothing created | `AlertWebhookTest` | Planned (Het) |
| TC-FR-03.1d | Equivalence (invalid) | Each required field left out | 400; nothing created | `AlertWebhookTest` | Planned (Het) |
| TC-NFR-04.2a | Negative | Register a tool and send alerts while capturing the logs | The token never appears in the logs | `AlertWebhookTest` | Planned (Het) |

### Rate limits (NFR-04.4, D42): boundary values

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-NFR-04.4a | Boundary value | The 5th login attempt within a minute for one IP and username | Answered normally | `RateLimitTest` | Planned (Het) |
| TC-NFR-04.4b | Boundary value | The 6th attempt in the same minute | 429 with `Retry-After` | `RateLimitTest` | Planned (Het) |
| TC-NFR-04.4c | Boundary value | The next attempt after the minute has passed | Answered normally again | `RateLimitTest` | Planned (Het) |
| TC-NFR-04.4d | Equivalence | Another username from the same IP, while the first is limited | Not limited | `RateLimitTest` | Planned (Het) |
| TC-NFR-04.4e | Boundary value | Webhook calls for one tool token just under and just over its limit | Under passes; over gets 429 | `RateLimitTest` | Planned (Het) |

### Deduplication and grouping (FR-03.2 to FR-03.4): decision table

| Rule | Same source and event ID seen before? | Same content? | Correlation key given? | Key matches an active incident on that service? | Expected |
| --- | --- | --- | --- | --- | --- |
| R1 | Yes | Yes | – | – | The existing result is returned; nothing new is created |
| R2 | Yes | No | – | – | 409; nothing changes |
| R3 | No | – | Yes | Yes | Linked to that incident with a timeline entry; neither clock resets |
| R4 | No | – | Yes | Only a RESOLVED or CLOSED one | A new incident |
| R5 | No | – | Yes | No | A new incident |
| R6 | No | – | No | – | A new incident |

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-03.2a | Decision table | R1 and R2 | As in the table | `AlertGroupingTest` | Planned (Manan) |
| TC-FR-03.3a | Decision table | R3 | As in the table | `AlertGroupingTest` | Planned (Manan) |
| TC-FR-03.4a | Decision table | R4, R5, and R6 | As in the table | `AlertGroupingTest` | Planned (Manan) |
| TC-FR-03.2b | Concurrency | The same event sent twice at the same moment | One alert row; one incident | `AlertGroupingTest` (Testcontainers) | Planned (Manan) |
| TC-FR-03.3b | Concurrency | Two different events with the same key at the same moment | One incident containing both | `AlertGroupingTest` (Testcontainers) | Planned (Manan) |

### On-call (FR-15, FR-05.1): boundary values

Shift A runs 09:00 to 17:00, followed by a gap until shift B starts at 18:00.

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-15.2a | Boundary value | An incident created at 09:00:00 | Assigned to A | `OnCallServiceTest` | Planned (Utsav B) |
| TC-FR-15.2b | Boundary value | Created at 16:59:59 | Assigned to A | `OnCallServiceTest` | Planned (Utsav B) |
| TC-FR-15.2c | Boundary value | Created at 17:00:00 | Not A: the fallback contact, since it falls in the gap | `OnCallServiceTest` | Planned (Utsav B) |
| TC-FR-15.2d | Equivalence | Created at 17:30, in the gap | The fallback contact | `OnCallServiceTest` | Planned (Utsav B) |
| TC-FR-15.1a | Equivalence (invalid) | A shift overlapping an existing one | 400; adjacent shifts are allowed | `OnCallServiceTest` | Planned (Utsav B) |
| TC-FR-05.1a | Equivalence | Any assignment at creation | The assignment is on the timeline, and the assignee is notified | `OnCallServiceTest` | Planned (Utsav B) |

### Notifications (FR-07.1, D33)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-07.1a | Equivalence | An incident is assigned | The assignee has one unread notification | `NotificationTest` | Planned (Utsav D) |
| TC-FR-07.1b | Negative | Another user requests that notification | 403 or 404; they never see it | `NotificationTest` | Planned (Utsav D) |
| TC-FR-07.1c | Equivalence | Mark it as read | The unread count goes down by one | `NotificationTest` | Planned (Utsav D) |
| TC-FR-07.8a | Negative | The change that caused the notification rolls back | No notification is left | `NotificationTest` | Planned (Utsav D) |

### Monitors and heartbeats (FR-22, D29): boundary values

The monitor raises an alert after N = 3 consecutive failures.

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-22.1a | Boundary value | 2 failures in a row | Nothing raised | `MonitorCheckTest` | Planned (Manush) |
| TC-FR-22.1b | Boundary value | 3 failures in a row | One alert and one incident | `MonitorCheckTest` | Planned (Manush) |
| TC-FR-22.1c | Boundary value | A 4th failure | No second alert | `MonitorCheckTest` | Planned (Manush) |
| TC-FR-22.1d | Equivalence | Fail, fail, pass, fail | Nothing raised: the count restarts | `MonitorCheckTest` | Planned (Manush) |
| TC-FR-22.2a | State transition | The service recovers | The alert is updated and a timeline entry added | `MonitorCheckTest` | Planned (Manush) |
| TC-FR-22.3a | State transition | After recovery | The incident stays open until a person resolves it | `MonitorCheckTest` | Planned (Manush) |
| TC-HBa | Boundary value | A ping 1 second before due time plus grace | Nothing raised | `HeartbeatTest` | Planned (Manush) |
| TC-HBb | Boundary value | No ping by due time plus grace | An alert is raised | `HeartbeatTest` | Planned (Manush) |

---

## Sprint 3

### Incident lifecycle (FR-06): state transition table

Rows are the current status, columns the requested one. ✓ is accepted; every ✗ is rejected with the
incident unchanged. OPEN → ACKNOWLEDGED happens only through acknowledgement.

| From \ To | OPEN | ACKNOWLEDGED | INVESTIGATING | IDENTIFIED | MONITORING | RESOLVED | CLOSED |
| --- | --- | --- | --- | --- | --- | --- | --- |
| OPEN | ✗ | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ |
| ACKNOWLEDGED | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ | ✗ |
| INVESTIGATING | ✗ | ✗ | ✗ | ✓ | ✓ | ✗ | ✗ |
| IDENTIFIED | ✗ | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ |
| MONITORING | ✗ | ✗ | ✓ | ✗ | ✗ | ✓ | ✗ |
| RESOLVED | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ |
| CLOSED | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |

8 accepted pairs and 41 rejected ones, including the 7 self-transitions.

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-06.3a | State transition | Each of the 8 ✓ pairs | Accepted; one timeline entry with actor and time | `TransitionPolicyTest` (parameterized) | Planned (Utsav D) |
| TC-FR-06.4a | State transition | Each of the 41 ✗ pairs | Rejected; status and timeline unchanged | `TransitionPolicyTest` (parameterized) | Planned (Utsav D) |
| TC-FR-06.4b | Negative | A change sent with a stale incident version | 409; nothing changes | `IncidentStatusTest` | Planned (Utsav D) |
| TC-FR-06.1a | Scenario | Walk OPEN through to CLOSED on the normal path | Every status saved and shown | `IncidentStatusTest` | Planned (Utsav D) |

### Acknowledgement (FR-06, FR-07.4, FR-16.2, US-08)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-US-08a | Equivalence | Acknowledge an OPEN incident | ACKNOWLEDGED; the acknowledger becomes the assignee | `AcknowledgeTest` | Planned (Utsav D) |
| TC-FR-16.2a | Equivalence | Acknowledge at minute 10 | The response clock stops at 10; the resolution clock keeps running | `AcknowledgeTest` | Planned (Utsav D) |
| TC-US-08b | Equivalence (invalid) | Acknowledge an incident that isn't OPEN | 409 | `AcknowledgeTest` | Planned (Utsav D) |
| TC-US-08c | Concurrency | Two people acknowledge at the same moment | One gets 200, the other 409 | `AcknowledgeTest` (Testcontainers) | Planned (Utsav D) |
| TC-ACK-OWNER | Scenario | A is assigned; escalation notifies B; B acknowledges first | B becomes the assignee; escalation alone never changed it | `AcknowledgeTest` | Planned (Utsav D, Utsav B) |

### Severity (FR-04.2)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-04.2a | Equivalence | Change the severity with a reason | Saved, with actor and reason on the timeline; SLA targets unchanged | `SeverityChangeTest` | Planned (Utsav D) |
| TC-FR-04.2b | Equivalence (invalid) | Change it with no reason | 400; nothing changes | `SeverityChangeTest` | Planned (Utsav D) |

### Escalation (FR-07.3 to FR-07.7): decision table

| Rule | Status OPEN? | Acknowledged? | Deadline passed? | Another level left? | Expected |
| --- | --- | --- | --- | --- | --- |
| E1 | Yes | No | Yes | Yes | Notify the next level; timeline entry; version increases; assignee unchanged |
| E2 | Yes | No | No | – | Nothing |
| E3 | Yes | Yes | – | – | Nothing |
| E4 | No (e.g. RESOLVED) | – | – | – | Nothing |
| E5 | Yes | No | Yes | No | Notify the fallback once; the incident stays OPEN and can still be acknowledged |

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-07.3a | Decision table | E1 | As in the table | `EscalationTest` | Planned (Utsav B) |
| TC-FR-07.7a | Decision table | E2, E3, and E4 | Nothing is sent or saved | `EscalationTest` | Planned (Utsav B) |
| TC-FR-07.5a | Decision table | E5 | As in the table | `EscalationTest` | Planned (Utsav B) |
| TC-FR-07.6a | Integration | Restart with an overdue step | The step runs once; the next level gets its full waiting time | `EscalationRestartTest` (Testcontainers) | Planned (Utsav B) |
| TC-FR-07.4a | Concurrency | Escalation and acknowledgement at the same moment, in both orders | The loser rolls back; no escalation after an accepted acknowledgement | `EscalationTest` (Testcontainers) | Planned (Utsav B, Utsav D) |

### Reassignment and roles (FR-05.2 to FR-05.5, D39)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-05.3a | Equivalence | Reassign an incident | The new owner is notified; the timeline shows from, to, by, and when; no clock restarts | `ReassignTest` | Planned (Het) |
| TC-FR-05.4a | Equivalence | Assign or transfer an incident role | On the timeline with actor and time | `IncidentRoleTest` | Planned (Het) |
| TC-FR-01.3a | Negative | A user with an incident role calls an Admin-only endpoint | 403: the role gives no admin rights | `IncidentRoleTest` | Planned (Het) |
| TC-FR-05.5a | Equivalence | One person holds two roles on a SEV2, then on a SEV4 | A warning on the SEV2; none on the SEV4; both saved | `IncidentRoleTest` | Planned (Het) |

### Comments and corrections (FR-08, FR-09)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-09.1a | Concurrency | Two people comment at the same moment | Both saved; the incident version is unchanged | `CommentTest` (Testcontainers) | Planned (Manan) |
| TC-FR-08.3c | Equivalence | Correct an entry | A new linked entry; the original is unchanged | `CommentTest` | Planned (Manan) |
| TC-FR-09.2a | Equivalence | Comment on a CLOSED incident | Accepted; the incident stays CLOSED | `CommentTest` | Planned (Manan) |
| TC-FR-09.2b | Negative | A Viewer or a caller with no token comments | 403 or 401 | `SecurityMatrixTest` | Planned (Manan) |

### Checks from several regions (D36): boundary values on the count of failing regions

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-D36a | Boundary value | 0 of 3 regions failing | Up | `RegionVerdictTest` | Planned (Manush) |
| TC-D36b | Boundary value | 1 of 3 failing | Up; nothing raised | `RegionVerdictTest` | Planned (Manush) |
| TC-D36c | Boundary value | 2 of 3 failing | Down; one alert | `RegionVerdictTest` | Planned (Manush) |
| TC-D36d | Boundary value | 3 of 3 failing | Down; still one alert | `RegionVerdictTest` | Planned (Manush) |

---

## White-box testing

Two rule methods get basis path testing, done by their owners once the code exists:

| Method | Owner |
| --- | --- |
| The transition check (`from`, `to` → allowed or the reason it is not) | Utsav D |
| The SLA check (target, start, stop, now → running, met, or breached) | Utsav D and Manan |

For each one:
1. Draw its control-flow graph and add the picture to `docs/testing/`.
2. Work out the cyclomatic complexity, V(G) = edges − nodes + 2.
3. List V(G) independent paths, and write one test per path. Name them here as `TC-WB-…`.
4. Check that JaCoCo shows every branch of the method covered.
5. Run PIT on the class and record the mutation score in the sprint's test report.
