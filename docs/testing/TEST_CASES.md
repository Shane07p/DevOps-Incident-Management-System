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
| TC-FR-01.1k | Equivalence | The login form submitted empty | Field errors shown; no request sent | `LoginPage.test.tsx` (shows a message under each empty field and sends nothing) | Automated |
| TC-FR-01.1l | Equivalence | The server answers 401 to the login form | "Invalid username or password" shown | `LoginPage.test.tsx` (says the username or password is wrong without saying which) | Automated |
| TC-FR-01.1m | Concurrency | Log out while an Admin's role change for the same user commits | The role change stays; the token version counts both | `AuthServiceLogoutTest.logoutKeepsARoleChangedMeanwhile` | Automated |

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
| TC-FR-01.2a | Decision table | Every cell above | The status in the cell | `SecurityMatrixTest.endpointSecurityMatrix` | Automated |
| TC-FR-01.2b | Decision table | Admin-only paths as Engineer and Viewer | 403 | `AuthControllerTest.adminOnlyPathsRefuseOtherRoles` | Automated |
| TC-FR-01.2c | Decision table | Viewer creates an incident | 403 | `AuthControllerTest.viewerCannotCreateIncidents` | Automated |
| TC-FR-01.2d | Equivalence | Health check and API docs with no token | 200 | `AuthControllerTest.healthAndApiDocsNeedNoToken` | Automated |

### User administration (US-01)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-US-01a | Boundary value | Password of 7 characters | 400 naming `password` | `UserAdminControllerTest.passwordBelowEightCharactersReturns400` | Automated |
| TC-US-01b | Boundary value | Password of 8 characters | 201 | `UserAdminControllerTest.passwordAtEightCharactersReturns201` | Automated |
| TC-US-01c | Boundary value | Password of 72 characters | 201 | `UserAdminControllerTest.passwordAtSeventyTwoCharactersReturns201` | Automated |
| TC-US-01d | Boundary value | Password of 73 characters | 400 | `UserAdminControllerTest.passwordAboveSeventyTwoCharactersReturns400` | Automated |
| TC-US-01e | Equivalence (invalid) | Username that differs from an existing one only in case | 409 | `UserAdminControllerTest.duplicateUsernameReturns409RegardlessOfCase` | Automated |
| TC-US-01f | Equivalence | Role `ADMIN`, `ENGINEER`, `VIEWER`; then an unknown role | 201 for each valid role; 400 for the unknown one | `UserAdminControllerTest.validRolesReturn201UnknownRoleReturns400` | Automated |
| TC-US-01g | State transition | Change a user's role, then reuse their old token | 401; their new login carries the new role | `UserAdminControllerTest.roleChangeIncrementsTokenVersionAndInvalidatesOldToken` | Automated |
| TC-US-01h | Negative | Creating a user and changing a role | Each writes one audit entry; the password never appears in it or in logs | `UserAdminControllerTest.createUserAndRoleChangeWriteAuditEntries` | Automated |
| TC-US-01i | Boundary value | The Users page form with a 7-character password | "Use at least 8 characters"; nothing sent | `UsersPage.test.tsx` | Automated |
| TC-US-01j | Equivalence (invalid) | The server answers 409 to a new member's email | The message shows under the email field | `UsersPage.test.tsx` | Automated |
| TC-US-01k | State transition | Pick a new role, cancel, then pick it again and confirm | Nothing is sent until confirmed; then one `PUT` with the new role | `UsersPage.test.tsx` | Automated |
| TC-US-01l | Boundary value | Password of 40 × `é`: 40 characters but 80 bytes | 400 naming `password`, not a 500 from BCrypt | `UserAdminControllerTest.passwordOverSeventyTwoBytesReturns400` | Automated |

### Service catalog (FR-14, US-05)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-14.1a | Equivalence | Valid service with targets for SEV1 to SEV4 | 201; the service can be chosen for incidents | `ServiceControllerTest.adminCreatesServiceWithAudit` | Automated |
| TC-FR-14.1b | Equivalence (invalid) | Targets missing for one severity | 400 naming the severity | `ServiceControllerTest.missingSeverityTargetIsRejected` | Automated |
| TC-FR-14.1c | Boundary value | Resolution target equal to the response target | Accepted | `DatabaseSchemaTest.slaTargetResolutionMayEqualResponse`; `ServiceControllerTest` | Automated (database); no API test yet |
| TC-FR-14.1d | Boundary value | Resolution target one minute below the response target | 400 | `DatabaseSchemaTest.slaTargetResolutionBelowResponseIsRejected`; `ServiceControllerTest.resolutionShorterThanResponseIsRejected` | Automated |
| TC-FR-14.1e | Boundary value | Response target of 0, then 1 minute | 0 rejected; 1 accepted | `DatabaseSchemaTest.slaTargetRejectsZeroResponse`; `ServiceControllerTest` | Automated (database); no API test yet |
| TC-FR-14.1f | Equivalence | Criticality `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`; then an unknown value | Valid values accepted; unknown gives 400 | `ServiceControllerTest` | No test yet: only a missing criticality is checked |
| TC-FR-14.1g | Equivalence (invalid) | A second service with the same name | 409 | `DatabaseSchemaTest.duplicateServiceNameIsRejected`; `ServiceControllerTest.duplicateServiceNameIsRejected` | Automated |
| TC-FR-14.1h | Negative | Update a service's targets | One audit entry showing before and after | `ServiceControllerTest.adminUpdatesService` | Automated |
| TC-FR-14.1i | Equivalence | The services form with a resolution target below its response target | A field error; nothing sent | `ServicesPage.test.tsx` | Automated |

### Incidents (FR-02, FR-04.1)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-02.1a | Equivalence | Title, description, service, and severity all given | 201 with status OPEN | `IncidentControllerTest` | Automated |
| TC-FR-02.1b | Equivalence (invalid) | Each required field left out, one at a time | 400 naming that field; nothing saved | `IncidentControllerTest` | Automated |
| TC-FR-02.1c | Negative | The audit write fails during creation | No incident and no timeline entry saved | `IncidentCreationRollbackTest` | Automated |
| TC-FR-02.2a | Equivalence | Two incidents created one after the other | Different IDs and `INC-` references; reporter and time from the request | `IncidentControllerTest` | Automated |
| TC-FR-02.2b | Equivalence | List incidents | Newest first | `IncidentControllerTest` | Automated |
| TC-FR-04.1a | Equivalence | Severity `SEV1` to `SEV4`; then `SEV5` and blank | Valid ones accepted; others 400 | `IncidentControllerTest` | Automated |
| TC-FR-16.3a | Equivalence | Create an incident, then change the service's targets | The incident keeps the targets copied at creation | `IncidentTest` | Automated |
| TC-FR-02.1d | Equivalence | The incident form submitted with missing fields | Field errors shown; nothing sent | `IncidentsPage.test.tsx` | Automated |
| TC-FR-01.2e | Equivalence | A Viewer opens the app | No "Declare incident" button; `/incidents/new` refused | `IncidentsPage.test.tsx` | Automated |

### SLA clocks (FR-16): boundary values with a controlled clock

Target T is the copied target in minutes. Exceeding T is a breach; reaching it exactly is not.

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-16.3b | Boundary value | Elapsed time T − 1 minute | Not breached | `SlaTest` | Automated |
| TC-FR-16.3c | Boundary value | Elapsed time exactly T | Not breached | `SlaTest` | Automated |
| TC-FR-16.3d | Boundary value | Elapsed time T + 1 second | Breached | `SlaTest` | Automated |
| TC-FR-16.4a | Equivalence | An incident open across a night and a weekend | All elapsed time counts | `SlaTest` | Automated |
| TC-FR-16.1a | Equivalence | A new incident | `responseDueAt` and `resolutionDueAt` are creation time plus each target | `IncidentTest` | Automated |

### Append-only records (FR-08, FR-13)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-08.3a | Negative | Update or delete a timeline entry directly in the database | Rejected | `DatabaseSchemaTest.timelineEntryCannotBeUpdated`, `timelineEntryCannotBeDeleted` | Automated |
| TC-FR-08.3b | Negative | Truncate the timeline or the audit log | Rejected | `DatabaseSchemaTest.timelineCannotBeTruncated`, `auditLogCannotBeTruncated` | Automated |
| TC-FR-13a | Negative | Update or delete an audit entry | Rejected | `DatabaseSchemaTest.auditEntryCannotBeUpdated`, `auditEntryCannotBeDeleted` | Automated |
| TC-FR-13b | Integration | `AuditService.record` inside a transaction that rolls back | The audit entry is rolled back too; outside a transaction it throws | `AuditServiceTest` | Automated |
| TC-FR-13c | Integration | Save an on-call schedule that adds or removes a shift, or changes the fallback contact | One `ON_CALL_SCHEDULE_UPDATED` entry naming the actor, the service, and each change | `OnCallServiceTest` (four cases), `ServiceOnCallControllerTest.updateScheduleRecordsAuditEntry` | Automated |
| TC-FR-13d | Equivalence | Save the same schedule again, its shifts in another order | No audit entry | `OnCallServiceTest.unchangedScheduleProducesZeroAuditEntries`, `ServiceOnCallControllerTest.updateScheduleRecordsAuditEntry` | Automated |
| TC-FR-13e | Integration | The save is rejected, or its audit entry fails | Nothing changes: no entry, and the old schedule stays | `OnCallServiceTest.scheduleValidationFailureLeavesNoAuditEntry`, `auditFailureRollsBackScheduleChanges` | Automated |

---

## Sprint 2

### Tools and alert intake (FR-03.1, US-02, D31, D32)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-03.1a | Equivalence | Register a tool | The token is shown once; later reads never return it; only its hash is stored | `ToolControllerTest` | Automated |
| TC-FR-03.1b | Equivalence | An alert with a valid token and all required fields | Accepted | `AlertWebhookTest` | Automated |
| TC-FR-03.1c | Equivalence (invalid) | Missing token, wrong token, revoked token | 401; nothing created | `AlertWebhookTest` | Automated |
| TC-FR-03.1d | Equivalence (invalid) | Each required field left out | 400; nothing created | `AlertWebhookTest` | Automated |
| TC-NFR-04.2a | Negative | Register a tool and send alerts while capturing the logs | The token never appears in the logs | `AlertWebhookTest` | Automated |
| TC-US-02a | Negative | Register a tool, copy its token, then close the dialog | The token is gone from the page; opening the dialog again starts a new registration | `ToolsPage.test.tsx` | Automated |
| TC-US-02b | State transition | Revoke a token: cancel the confirmation, then confirm it | Nothing is sent on cancel; one revoke request on confirm | `ToolsPage.test.tsx` | Automated |

### Rate limits (NFR-04.4, D42): boundary values

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-NFR-04.4a | Boundary value | The 5th login attempt within a minute for one IP and username | Answered normally | `RateLimitTest` | Automated |
| TC-NFR-04.4b | Boundary value | The 6th attempt in the same minute | 429 with `Retry-After` | `RateLimitTest` | Automated |
| TC-NFR-04.4c | Boundary value | The next attempt after the minute has passed | Answered normally again | `RateLimitTest` | Automated |
| TC-NFR-04.4d | Equivalence | Another username from the same IP, while the first is limited | Not limited | `RateLimitTest` | Automated |
| TC-NFR-04.4e | Boundary value | Webhook calls for one tool token just under and just over its limit | Under passes; over gets 429 | `RateLimitTest` | Automated |

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
| TC-FR-03.2a | Decision table | R1 and R2 | As in the table | `AlertWebhookTest.repeatedEventWithSameCanonicalContent_isIdempotent`, `repeatedEventWithChangedPayload_returnsConflict` | Automated |
| TC-FR-03.3a | Decision table | R3 | As in the table | `AlertWebhookTest.alertsWithSameSourceServiceAndCorrelationKey_shareActiveIncident`; `AlertGroupingTest.joiningAnIncidentLeavesItsClocksAlone` | Automated |
| TC-FR-03.4a | Decision table | R4, R5, and R6 | As in the table | `AlertGroupingTest.aKeyMatchingOnlyAFinishedIncidentOpensANewOne`; `AlertWebhookTest` (different source, different service, no key) | Automated |
| TC-FR-03.2b | Concurrency | The same event sent twice at the same moment | One alert row; one incident | `AlertGroupingTest.sameEventTwiceAtOnceMakesOneAlertAndOneIncident` | Automated |
| TC-FR-03.3b | Concurrency | Two different events with the same key at the same moment | One incident containing both | `AlertGroupingTest.twoEventsWithOneKeyAtOnceShareOneIncident` | Automated |
| TC-FR-03.2c | Concurrency | The same source and event ID for two services at the same moment | One is accepted; the other gets 409, not 500 | `AlertGroupingTest.oneEventForTwoServicesAtOnceGets409` | Automated |

### On-call (FR-15, FR-05.1): boundary values

Shift A runs 09:00 to 17:00, followed by a gap until shift B starts at 18:00.

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-15.2a | Boundary value | An incident created at 09:00:00 | Assigned to A | `OnCallServiceTest` | Automated |
| TC-FR-15.2b | Boundary value | Created at 16:59:59 | Assigned to A | `OnCallServiceTest` | Automated |
| TC-FR-15.2c | Boundary value | Created at 17:00:00 | Not A: the fallback contact, since it falls in the gap | `OnCallServiceTest` | Automated |
| TC-FR-15.2d | Equivalence | Created at 17:30, in the gap | The fallback contact | `OnCallServiceTest` | Automated |
| TC-FR-15.1a | Equivalence (invalid) | A shift overlapping an existing one | 400; adjacent shifts are allowed | `OnCallServiceTest` | Automated |
| TC-FR-15.1b | Equivalence (invalid) | The On-call page form with a shift overlapping an existing one | The error shows on the form; nothing sent | `SchedulesPage.test.tsx` | Automated |
| TC-FR-15.1c | Concurrency | Two schedule saves for one service at once | The second waits for the first; the result is one schedule, never a mix of both | `OnCallServiceTest.concurrentScheduleSavesDoNotMix` | Automated |
| TC-FR-15.2e | Boundary value | The On-call page at a shift's first moment, one second before its end, and at its end | "On call now" matches the server's rule: start inclusive, end exclusive | `schedule.test.ts` | Automated |
| TC-FR-05.1a | Equivalence | Any assignment at creation | The assignment is on the timeline, and the assignee is notified | `OnCallServiceTest`; `NotificationTest.tcFr07_1a_assignedIncidentCreatesUnreadNotificationForAssignee` | Automated |

### Notifications (FR-07.1, D33)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-07.1a | Equivalence | An incident is assigned | The assignee has one unread notification | `NotificationTest.tcFr07_1a_assignedIncidentCreatesUnreadNotificationForAssignee` | Automated |
| TC-FR-07.1b | Negative | Another user requests that notification | 404; they never see it | `NotificationTest.tcFr07_1b_userIsolationCannotAccessOtherUsersNotification` | Automated |
| TC-FR-07.1c | Equivalence | Mark it as read | The unread count goes down by one | `NotificationTest.tcFr07_1c_markAsReadDecrementsUnreadCount` | Automated |
| TC-FR-07.1d | Equivalence | The bell with 1, 0, and 12 unread notifications | "1"; no count; "9+", and the label says how many | `NotificationBell.test.tsx` | Automated |
| TC-FR-07.1e | State transition | Open an unread notification, then a read one | One mark-read request for the unread one, none for the read one; each goes to its incident | `NotificationBell.test.tsx` | Automated |
| TC-FR-07.8a | Negative | The change that caused the notification rolls back | No notification is left | `NotificationTest.tcFr07_8a_transactionRollbackCleansUpNotification` | Automated |

### Monitors and heartbeats (FR-22, D29): boundary values

The monitor raises an alert after N = 3 consecutive failures.

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-22.1a | Boundary value | 2 failures in a row | Nothing raised | `MonitorCheckTest.twoFailuresRaiseNothing` (Testcontainers) | Automated |
| TC-FR-22.1b | Boundary value | 3 failures in a row | One alert and one incident | `MonitorCheckTest.thirdFailureRaisesOneAlertAndOneIncident` (Testcontainers) | Automated |
| TC-FR-22.1c | Boundary value | A 4th failure | No second alert | `MonitorCheckTest.fourthFailureAddsNoSecondAlert` (Testcontainers) | Automated |
| TC-FR-22.1d | Equivalence | Fail, fail, pass, fail | Nothing raised: the count restarts | `MonitorCheckTest.passingCheckRestartsTheCount` (Testcontainers) | Automated |
| TC-FR-22.2a | State transition | The service recovers | The alert is updated and a timeline entry added | `MonitorCheckTest.recoveryUpdatesTheAlertAndTheTimeline` (Testcontainers) | Automated |
| TC-FR-22.3a | State transition | After recovery | The incident stays open until a person resolves it | `MonitorCheckTest.recoveryLeavesTheIncidentOpen` (Testcontainers) | Automated |
| TC-FR-22.1e | Boundary value | A monitor interval of 29 seconds, and of 30 | 29 is refused with a field error; 30 is accepted | `MonitorControllerTest.intervalBoundary` | Automated |
| TC-FR-22.1f | Negative | A monitor URL on our own network: loopback, private ranges, `169.254.169.254`, IPv6 local | Refused on save with a field error, and never contacted by a check | `MonitorTargetGuardTest`, `HttpProbeTest.refusedTargetIsNotContacted`, `MonitorControllerTest.internalUrlIsRefused` | Automated |
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
| TC-FR-06.3a | State transition | Each of the 8 ✓ pairs | Accepted; one timeline entry with actor and time | `TransitionPolicyTest` (parameterized) | Planned (Utsav B) |
| TC-FR-06.4a | State transition | Each of the 41 ✗ pairs | Rejected; status and timeline unchanged | `TransitionPolicyTest` (parameterized) | Planned (Utsav B) |
| TC-FR-06.4b | Negative | A change sent with a stale incident version | 409; nothing changes | `IncidentStatusTest` | Planned (Utsav B) |
| TC-FR-06.1a | Scenario | Walk OPEN through to CLOSED on the normal path | Every status saved and shown | `IncidentStatusTest` | Planned (Utsav B) |

### Acknowledgement (FR-06, FR-07.4, FR-16.2, US-08)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-US-08a | Equivalence | Acknowledge an OPEN incident | ACKNOWLEDGED; the acknowledger becomes the assignee | `AcknowledgeTest` | Planned (Utsav B) |
| TC-FR-16.2a | Equivalence | Acknowledge at minute 10 | The response clock stops at 10; the resolution clock keeps running | `AcknowledgeTest` | Planned (Utsav B) |
| TC-US-08b | Equivalence (invalid) | Acknowledge an incident that isn't OPEN | 409 | `AcknowledgeTest` | Planned (Utsav B) |
| TC-US-08c | Concurrency | Two people acknowledge at the same moment | One gets 200, the other 409 | `AcknowledgeTest` (Testcontainers) | Planned (Utsav B) |
| TC-ACK-OWNER | Scenario | A is assigned; escalation notifies B; B acknowledges first | B becomes the assignee; escalation alone never changed it | `AcknowledgeTest` | Planned (Utsav B) |

### Severity (FR-04.2)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-04.2a | Equivalence | Change the severity with a reason | Saved, with actor and reason on the timeline; SLA targets unchanged | `SeverityChangeTest` | Planned (Utsav B) |
| TC-FR-04.2b | Equivalence (invalid) | Change it with no reason | 400; nothing changes | `SeverityChangeTest` | Planned (Utsav B) |

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
| TC-FR-07.3a | Decision table | E1 | As in the table | `EscalationIntegrationTest.tcFr07_3a_ruleE1_notifiesNextLevelAndIncrementsVersionAndAssigneeUnchanged` | Automated |
| TC-FR-07.3b | Equivalence | The Escalation policies page as a Viewer | The levels in paging order, with when each is paged, then the fallback contact; no form | `EscalationPage.test.tsx` | Automated |
| TC-FR-07.3c | State transition | An Admin moves a level up, removes one, adds one, and saves | One `PUT` with the levels numbered 1, 2 in their new order; the last level can't be removed | `EscalationPage.test.tsx` | Automated |
| TC-FR-07.3d | Boundary value | A wait of 0 minutes, and a level with no contact | Field errors; nothing sent | `EscalationPage.test.tsx` | Automated |
| TC-FR-07.7a | Decision table | E2, E3, and E4 | Nothing is sent or saved | `EscalationIntegrationTest.tcFr07_7a_rulesE2_E3_E4_nothingSentOrSavedWhenIneligible` | Automated (the status is set directly until #83 and #84) |
| TC-FR-07.5a | Decision table | E5 | As in the table | `EscalationIntegrationTest.tcFr07_5a_ruleE5_notifiesFallbackOnceAndIncidentStaysOpen` | Automated |
| TC-FR-07.6a | Integration | Restart with an overdue step | The step runs once; the next level gets its full waiting time | `EscalationIntegrationTest.tcFr07_6a_restartWithOverdueStepRunsOnceAndGivesFullWaitToNextLevel`; `workerEscalatesOverdueTaskAndCommits` | Automated |
| TC-FR-07.4a | Concurrency | Escalation and acknowledgement at the same moment, in both orders | The loser rolls back; no escalation after an accepted acknowledgement | `EscalationIntegrationTest.tcFr07_4a_concurrency_escalationRacingAcknowledgement` | Automated (the acknowledgement is a direct status change until #83) |
| TC-D24a | Integration | A PENDING outbox event, with RabbitMQ running | A consumer bound to `incident.#` receives it with its payload, routing key, and message id; it becomes SENT | `OutboxRelayTest.aPendingEventIsPublishedConsumedAndMarkedSent` | Automated |
| TC-D24b | Equivalence | Two PENDING events | They leave oldest first | `OutboxRelayTest.eventsLeaveOldestFirst` | Automated |
| TC-D24c | Negative | RabbitMQ does not confirm an event | It stays PENDING for the next pass | `OutboxRelayTest.anEventTheBrokerDoesNotConfirmStaysPending` | Automated |

### Reassignment and roles (FR-05.2 to FR-05.5, D39)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-05.3a | Equivalence | Reassign an incident | The new owner is notified; the timeline shows from, to, by, and when; no clock restarts | `ReassignTest`, `ReassignRaceTest` | Automated |
| TC-FR-05.4a | Equivalence | Assign or transfer an incident role | On the timeline with actor and time | `IncidentRoleTest`, `IncidentRoleRaceTest` | Automated |
| TC-FR-01.3a | Negative | A user with an incident role calls an Admin-only endpoint | 403: the role gives no admin rights | `IncidentRoleTest` | Automated |
| TC-FR-05.5a | Equivalence | One person holds two roles on a SEV2, then on a SEV4 | A warning on the SEV2; none on the SEV4; both saved | `IncidentRoleTest` | Automated |

### Comments and corrections (FR-08, FR-09)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-09.1a | Concurrency | Two people comment at the same moment; comment and correction text at and over 5,000 characters | Both comments saved; the incident version is unchanged; 5,000 accepted and 5,001 rejected | `CommentConcurrencyTest.twoConcurrentCommentsArePersistedWithoutChangingIncidentVersion`; `IncidentControllerTest.commentsDoNotChangeIncidentVersion`, `commentAndCorrectionEnforce5000CharacterLimit` (Testcontainers) | Automated |
| TC-FR-08.3c | Equivalence | Correct an entry | A new linked entry; the original is unchanged | `IncidentControllerTest.correctionAddsLinkedEntryAndLeavesOriginalUnchanged` (Testcontainers) | Automated |
| TC-FR-09.2a | Equivalence | Comment on a CLOSED incident | Accepted; the incident stays CLOSED | `IncidentControllerTest.commentsAreAllowedOnClosedIncidents` (Testcontainers) | Automated |
| TC-FR-09.2b | Negative | A Viewer or a caller with no token comments | 403 or 401 | `SecurityMatrixTest.endpointSecurityMatrix`; `IncidentControllerTest.commentsDoNotChangeIncidentVersion` (Testcontainers) | Automated |

### Checks from several locations (D35, D36): boundary values on failing locations and checks in a row

The rules are Grafana alert rules, so a to f are checked by hand with the demo shop's fault switches, with a screenshot of each in the pull request; g is automated.

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-D36a | Boundary value | 1 of 3 locations failing, for 3 checks | Operational; nothing raised | Grafana alert rule, by hand | Planned (Manush) |
| TC-D36b | Boundary value | 2 of 3 failing, for 1 check | Operational; nothing raised | Grafana alert rule, by hand | Planned (Manush) |
| TC-D36c | Boundary value | 2 of 3 failing, for 2 checks in a row | Down; one critical alert | Grafana alert rule, by hand | Planned (Manush) |
| TC-D36d | Boundary value | 2 of 3 slower than the limit, for 2 checks | Operational; nothing raised | Grafana alert rule, by hand | Planned (Manush) |
| TC-D36e | Boundary value | 2 of 3 slower than the limit, for 3 checks in a row | Degraded; one warning alert | Grafana alert rule, by hand | Planned (Manush) |
| TC-D36f | Boundary value | 3 of 3 failing, still failing at the next evaluation | Still Down; no second alert | Grafana alert rule, by hand | Planned (Manush) |
| TC-D36g | Equivalence | Grafana posts the same firing alert again | Counted once; one incident | Intake test with a recorded Grafana payload | Planned (Manush) |

### AI SRE tool loop on demo data (Issue #91, FR-18.2)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-AI-91a | Scenario | Multi-step investigation with registered evidence tools | 200 with `status: completed`, verified hypotheses, and citations | `test_agent_loop.py::test_successful_multi_step_investigation_loop` | Automated |
| TC-AI-91b | Boundary value | Investigation loop exceeding step limit (N = 3) | Halts with `status: step_limit_exceeded`, `steps_taken == 3` | `test_agent_loop.py::test_step_limit_enforcement` | Automated |
| TC-AI-91c | Boundary value | Investigation loop exceeding wall-clock deadline | Halts with `status: timeout` | `test_agent_loop.py::test_wall_clock_timeout_enforcement` | Automated |
| TC-AI-91d | Boundary value | Cumulative tokens exceeding max budget | Halts with `status: token_budget_exhausted` | `test_agent_loop.py::test_token_budget_enforcement` | Automated |
| TC-AI-91e | Negative | Hypotheses citing fabricated/unknown evidence IDs | Unsupported hypotheses discarded; only verified citations retained | `test_agent_loop.py::test_dropping_hypotheses_when_evidence_fabricated` | Automated |
| TC-AI-91f | Negative | Tool call to unknown or unregistered tool name | Rejected safely with error description without crashing loop | `test_agent_loop.py::test_tool_registry_rejects_unknown_tool` | Automated |
| TC-AI-91g | Negative | Investigation where no evidence is found | Returns `status: no_evidence` and `hypotheses: []` | `test_agent_loop.py::test_empty_evidence_and_no_hypotheses` | Automated |
| TC-AI-91h | Integration | HTTP call to `POST /investigate` | 200 with `aiGenerated: true`, evidence, and hypotheses | `test_agent_loop.py::test_api_investigate_successful` | Automated |

## Sprint 4

The service map API (#154) brings its own cases in its pull request (#180).

### Orgs and sign-up (FR-01.4 to FR-01.6, D48)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-01.4a | Equivalence | Sign up with an email, password, name, and org name | 201; the org exists with a slug, the person is its Admin, and the response carries a token | `SignupControllerTest` | Automated |
| TC-FR-01.4b | Negative | Sign up with an email that exists, in another case | 409; nothing created | `SignupControllerTest` | Automated |
| TC-FR-01.4c | Equivalence | Two orgs with the same name | Slugs `same` and `same-2` | `SignupControllerTest`, `SlugsTest` | Automated |
| TC-FR-01.4d | Boundary | The sixth sign-up in a minute from one address | 429 | `SignupControllerTest` | Automated |
| TC-FR-01.4e | Equivalence | Sign in by email, in any case; the token | 200; the token carries the org; the role comes from the membership | `AuthControllerTest` | Automated |
| TC-FR-01.4f | Negative | Unknown email, wrong password, disabled membership | The same 401 "Invalid email or password" | `AuthControllerTest` | Automated |
| TC-FR-01.5a | Negative | Every endpoint with an id, called with another org's ids | 404, never 403, never the other org's data | `OrgIsolationTest` | Automated |
| TC-FR-01.5b | Negative | A body naming another org's service or people (owner, responder, fallback contact) | 400 or 404; nothing saved | `OrgIsolationTest` | Automated |
| TC-FR-01.5c | Equivalence | Every list, the audit log included | Only the caller's org's rows | `OrgIsolationTest`, `UserAdminControllerTest` | Automated |
| TC-FR-01.5d | Equivalence | Queries and `findById` in two orgs | Each org sees only its rows | `TenantFilteringTest` | Automated |
| TC-FR-01.5e | Equivalence | An alert sent with another org's tool token; overdue escalations in two orgs | Each lands in, and escalates in, its own org | `AlertWebhookOrgTest`, `EscalationWorkerOrgTest` | Automated |
| TC-FR-01.5f | Equivalence | Two orgs declare incidents | Each numbers from INC-1 | `IncidentNumberingTest` | Automated |
| TC-FR-01.5g | Equivalence | Migrating a database with users and services | Everything moves into "Dev org"; emails become `<username>@settle.local` | `OrganizationsMigrationTest` | Automated |
| TC-FR-01.6a | Equivalence | An Admin adds a member by email | 201 with the role; the member is in the Admin's org | `UserAdminControllerTest` | Automated |
| TC-FR-01.6b | Boundary | Demote the last enabled Admin | 409 "An org needs at least one Admin" | `UserAdminControllerTest`, `UserAdminServiceTest` | Automated |
| TC-FR-01.6c | Negative | Change the role of someone in another org | 404 | `UserAdminControllerTest` | Automated |
| TC-FR-01.6d | Concurrency | Two Admins demote each other at the same moment | The second waits for the first, then gets 409; one Admin remains | `LastAdminRaceTest` | Automated |
| TC-FR-01.4h | Concurrency | A slug or an email taken between the check and the insert | A taken slug is looked for again (up to 3 times); a taken email is 409 | `SignupServiceTest` | Automated |
| TC-FR-01.5h | Equivalence | Alert inbox in an org whose incident ids differ from its numbers | Each alert shows its incident's reference (INC-1), not its id | `AlertGroupingTest`, `AlertsPage.test.tsx` | Automated |
| TC-FR-01.4g | Equivalence | Sign-up page and sign in by email | The org is created and the app opens; the header shows the org; a taken email shows on its field; sign-in posts `email` | `SignupPage.test.tsx`, `LoginPage.test.tsx`, `TopBar.test.tsx` | Automated |

### Personal access tokens (FR-23, D38): decision table and boundary values

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-23.1a | Equivalence | Create a token named "Cursor" | 201 with the token (`pat_…`) once; only its SHA-256 hash is stored | `PersonalAccessTokenTest` | Planned (Het) |
| TC-FR-23.1b | Equivalence | Access tokens page: create a token, then close the dialog | The token is gone from the page; revoking sends nothing until it is confirmed | `AccessTokensPage.test.tsx` | Planned (Het) |
| TC-FR-23.2a | Equivalence | The token calls `GET /api/incidents` | 200, as its owner | `PersonalAccessTokenTest` | Planned (Het) |
| TC-FR-23.2b | Decision table | A Viewer's token calls an Engineer action | 403 | `SecurityMatrixTest` | Planned (Het) |
| TC-FR-23.2c | State transition | Revoke the token, or disable its owner, then use it | 401 | `PersonalAccessTokenTest` | Planned (Het) |
| TC-FR-23.2d | Boundary value | Use the token one second before, and exactly at, 90 days after creation (controlled `Clock`) | 200, then 401 | `PersonalAccessTokenTest` | Planned (Het) |
| TC-FR-23.2e | Boundary value | The 60th and the 61st request in one minute | 200, then 429 with `Retry-After` | `PersonalAccessTokenTest` | Planned (Het) |
| TC-FR-23.3a | Negative | A token calls `POST /api/me/tokens` or `GET /api/me/tokens` | Refused; nothing is created | `PersonalAccessTokenTest` | Planned (Het) |
| TC-FR-23.4a | Negative | Create and revoke a token, then read the captured logs | Two audit entries; the token is in no log line | `PersonalAccessTokenTest` | Planned (Het) |

### Deploy tracking (FR-20.1): boundary values on the 2-hour window

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-20.1a | Equivalence (invalid) | A deploy for an unknown service | 400 naming the service | `DeployWebhookTest` | Planned (Het) |
| TC-FR-20.1b | Equivalence | The same service, version, and time posted twice | Stored once | `DeployWebhookTest` | Planned (Het) |
| TC-FR-20.1c | Boundary value | Deploys 2 hours and 1 second, exactly 2 hours, and 1 second before the incident | The last two show with the incident; the first does not | `DeployWebhookTest` | Planned (Het) |
| TC-FR-20.1d | Equivalence | Link a deploy to an incident | One timeline entry | `DeployWebhookTest` | Planned (Het) |

### Runbooks (FR-25)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-25.1a | Equivalence | A runbook with three steps | Read back in the same order | `RunbookControllerTest` | Planned (Utsav D) |
| TC-FR-25.1b | Equivalence (invalid) | An empty title, or no steps | 400 with a field error | `RunbookControllerTest` | Planned (Utsav D) |
| TC-FR-25.1c | Decision table | A Viewer creates, edits, or deletes a runbook | 403 | `SecurityMatrixTest` | Planned (Utsav D) |
| TC-FR-25.1d | Equivalence | Create, edit, and delete a runbook | One audit entry each | `RunbookControllerTest` | Planned (Utsav D) |
| TC-FR-25.2a | Equivalence | Open an incident whose service has runbooks, as an Engineer and as a Viewer | Both see the runbooks; only the Engineer sees editing | `RunbooksPage.test.tsx` | Planned (Bhakti) |

### Logs and metrics through the backend (FR-26, FR-27, D34)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-26.1a | Equivalence | Each combination of service, level, text, and time range, against a stubbed Loki | The query is built from those parameters only; text from the user never becomes query syntax | `TelemetryServiceTest` | Planned (Dhruva) |
| TC-FR-26.1b | Equivalence | Logs page: set the filters, then click a field in a JSON line | The request carries the filters; the field becomes a filter | `LogsPage.test.tsx` | Planned (Himanshu) |
| TC-FR-26.1c | Negative | Run a query, then read the captured logs | The read token is in no log line | `TelemetryServiceTest` | Planned (Dhruva) |
| TC-FR-26.2a | Boundary value | A time range of exactly 24 hours, and of 24 hours and 1 second | Accepted; 400 | `TelemetryServiceTest` | Planned (Dhruva) |
| TC-FR-26.3a | Negative | The stubbed Grafana never answers | 503 within the timeout; other endpoints still answer | `TelemetryServiceTest` | Planned (Dhruva) |
| TC-FR-27.1a | Equivalence | Each metric (`requestRate`, `errorRate`, `latencyP50`, `latencyP95`) against a stubbed Prometheus | The query built for it; the series returned | `TelemetryServiceTest` | Planned (Dhruva) |
| TC-FR-27.2a | Equivalence | Incident page with no deploys, logs, or evidence | Each section shows its empty state; a Viewer sees no Link or add actions | `IncidentPage.test.tsx` | Planned (Bhakti) |
| TC-FR-27.3a | Negative | Metrics page while the API answers 503 | A clear message; the rest of the page works | `MetricsPage.test.tsx` | Planned (Himanshu) |

### Evidence links (FR-09.3)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-09.3a | Equivalence (invalid) | A `javascript:` link, and an `ftp:` link | 400 | `IncidentControllerTest` | Automated (17 controller tests passed) |
| TC-FR-09.3b | Concurrency | Two people add evidence to one incident at once | Both are saved; no version conflict | `EvidenceConcurrencyTest` | Automated |
| TC-FR-09.3c | Equivalence | Add evidence to a CLOSED incident | Accepted; the incident stays CLOSED | `IncidentControllerTest` | Automated (17 controller tests passed) |

### History search (FR-17)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-17.1a | Equivalence | A word found only in a title, only in a description, and only in a timeline entry | Each incident is found, with where it matched | `IncidentSearchTest` | Planned (Manan) |
| TC-FR-17.1b | Decision table | A query with each filter (service, severity, status) | Only incidents matching the query and the filter | `IncidentSearchTest` | Planned (Manan) |
| TC-FR-17.1c | Boundary value | 20 and 21 matches | One page, then two pages | `IncidentSearchTest` | Planned (Manan) |
| TC-FR-17.1d | Equivalence | A word that matches nothing | 200 with no results | `IncidentSearchTest` | Planned (Manan) |
| TC-FR-17.1e | Equivalence | Search page: set the filters, then reload | The filters stay in the URL; an empty result shows the empty state | `HistorySearchPage.test.tsx` | Planned (Himanshu) |

### AI-1 Find similar (FR-18, D44)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-D44a | Equivalence | Store and read back a 768-dimension vector | The same values | `DatabaseSchemaTest` | Planned (Manan) |
| TC-FR-18.1a | Equivalence | A stubbed AI service returns fixed embeddings | Up to 3 past incidents above the threshold, highest score first, labelled as AI | `SimilarIncidentsTest`, against PostgreSQL with pgvector | Planned (Manan) |
| TC-FR-18.2a | Equivalence | Incident page and declare form | Similar incidents are labelled as AI; the duplicate warning never blocks declaring; with AI off nothing shows | `SimilarIncidents.test.tsx` | Planned (Himanshu) |
| TC-FR-18.4a | Negative | The AI service is down, slow, or `AI_SERVICE_URL` is unset | The incident is still created; similar incidents come back empty, never as an error | `SimilarIncidentsTest` | Planned (Manan) |

### Public status page (FR-21, D45): decision table on who may publish

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-21.1a | Negative | `GET /api/public/status` for an incident with internal notes, comments, and names | None of them in the response | `PublicStatusTest` | Planned (Shane) |
| TC-FR-21.1b | Equivalence | Fixed monitor results over 90 days | The uptime percentage computed from them | `PublicStatusTest` | Planned (Shane) |
| TC-FR-21.1c | Equivalence | The status page with each health state; the incident page as a Viewer | Each state renders; the Viewer sees no posting form | `StatusPage.test.tsx` | Planned (Bhakti) |
| TC-FR-21.2a | Decision table | The incident's Lead, its Comms, an Admin, another Engineer, and a Viewer post a public update | Accepted for the first three, with a timeline entry; 403 for the other two. Until #86 lands: accepted for Engineers and Admins | `PublicStatusTest`, `SecurityMatrixTest` | Planned (Shane) |

### MCP server (FR-28, D38)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-28.1a | Equivalence | Each read tool against a stubbed REST API | The caller's token is forwarded; the result is shaped for the assistant | `test_mcp_server.py` | Planned (Himanshu) |
| TC-FR-28.1b | Negative | The API answers 401 or 403 | A readable tool error | `test_mcp_server.py` | Planned (Himanshu) |
| TC-FR-28.2a | Negative | List the server's tools | No write tool, and none that changes status, declares, or closes | `test_mcp_server.py` | Planned (Himanshu) |

### Pages: audit log, service map, SLA clocks

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-US-17a | Equivalence | Audit log page: set the filters and go to page 2 | The request carries the filters and the page | `AuditLogPage.test.tsx` | Planned (Utsav D) |
| TC-US-17b | Equivalence | Audit log page | Times in IST; no edit or delete controls | `AuditLogPage.test.tsx` | Planned (Utsav D) |
| TC-US-26a | Equivalence | Select a service on the map | Its blast radius is highlighted | `ServiceMapPage.test.tsx` | Planned (Utsav D) |
| TC-US-26b | Decision table | A Viewer opens the map and a service form | No dependency editing | `ServiceMapPage.test.tsx` | Planned (Utsav D) |
| TC-FR-16.3e | Boundary value | A controlled clock one second before, exactly at, and one second after the target | Time left; not breached at the target; Breached one second after | `SlaClock.test.tsx` | Planned (Shlok) |
| TC-FR-16.3f | Equivalence | Sort the board by severity, then by time left | The order follows each key | `IncidentsPage.test.tsx` | Planned (Shlok) |

### Audit log API (FR-13, #153)

| ID | Technique | Input or condition | Expected | Test | Status |
| --- | --- | --- | --- | --- | --- |
| TC-FR-13.2a | Equivalence | Admin requests audit log entries | 200 with actor display name, action, item type, item ID, details, and recorded timestamp | `AuditControllerTest.responseContainsAllRequiredFields` | Automated |
| TC-FR-13.2b | Ordering | Read entries with differing timestamps | Ordered newest first (recordedAt DESC) | `AuditControllerTest.adminReadsAuditEntriesNewestFirst` | Automated |
| TC-FR-13.2c | Boundary value | 51 seeded entries on page 0 | Returns exactly 50 entries for page 0 and totalElements 51 | `AuditControllerTest.auditEntriesArePaginatedInPagesOfFifty` | Automated |
| TC-FR-13.2d | State / Boundary | Page traversal across 52 records | Page boundaries do not duplicate or skip records under a stable dataset | `AuditControllerTest.pageBoundariesDoNotDuplicateOrSkipRecords` | Automated |
| TC-FR-13.2e | Equivalence | Filter by itemType | Returns only entries matching the specified item type | `AuditControllerTest.filtersByItemType` | Automated |
| TC-FR-13.2f | Equivalence | Filter by itemId | Returns only entries matching the specified item ID | `AuditControllerTest.filtersByItemId` | Automated |
| TC-FR-13.2g | Equivalence | Filter by actorId | Returns only entries initiated by the specified actor | `AuditControllerTest.filtersByActor` | Automated |
| TC-FR-13.2h | Boundary value | Filter by time range [from, to) | Returns entries within inclusive `from` and exclusive `to` bounds | `AuditControllerTest.filtersByTimeRange` | Automated |
| TC-FR-13.2i | Decision table | Combined itemType and actorId filters | Returns only records matching every supplied filter (AND semantics) | `AuditControllerTest.combinesFiltersUsingAndSemantics` | Automated |
| TC-FR-13.2j | Equivalence | Filter with no matching records | 200 with empty content and totalElements = 0 | `AuditControllerTest.emptyResultsReturnAnEmptyPage` | Automated |
| TC-FR-13.2k | Equivalence (invalid) | Negative page, invalid timestamp, from >= to, unknown itemType | 400 Bad Request with field errors | `AuditControllerTest.invalidPageAndTimeRangeReturn400` | Automated |
| TC-FR-01.2f | Decision table | Engineer calls `GET /api/audit` | 403 Forbidden | `SecurityMatrixTest.endpointSecurityMatrix` | Automated |
| TC-FR-01.2g | Decision table | Viewer calls `GET /api/audit` | 403 Forbidden | `SecurityMatrixTest.endpointSecurityMatrix` | Automated |
| TC-FR-01.2h | Decision table | Unauthenticated call to `GET /api/audit` | 401 Unauthorized | `SecurityMatrixTest.endpointSecurityMatrix` | Automated |
| TC-FR-13.3b | Negative | Repeated reads to `GET /api/audit` | Audit log row count and entry contents remain completely unchanged | `AuditControllerTest.readingAuditLogDoesNotChangeExistingEntries` | Automated |
| TC-FR-13.2l | Equivalence | System-generated audit record with null actor | 200 with null actorDisplayName, entry successfully returned | `AuditControllerTest.systemGeneratedEntryWithNullActorIsHandledCorrectly` | Automated |
| TC-FR-13.2m | Ordering | Entries with identical recordedAt timestamps | Secondary sort by id DESC produces deterministic ordering | `AuditControllerTest.equalTimestampsDeterministicOrdering` | Automated |
| TC-FR-13.3c | Negative | POST, PUT, DELETE requests to `/api/audit` | 405 Method Not Allowed; endpoint is strictly read-only | `AuditControllerTest.mutationMethodsAreNotExposed` | Automated |

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
