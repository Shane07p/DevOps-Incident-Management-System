# Requirements

## How to use this file

This is the **current requirements baseline**. [`requirements/Group-2.pdf`](requirements/Group-2.pdf) is
the version we submitted; where the two differ, this file wins (D30).

- **IDs never change.** A requirement that is merged or moved keeps its ID and says where it went,
  so links from `TRACEABILITY.csv`, the user stories, and the plans keep working. New requirements
  take the next free number. Every change is listed at the end.
- **Priority** is Must, Should, or Could. A sub-requirement's own priority overrides its parent's.
- User stories are in [user-stories.md](user-stories.md); survey findings and conflict resolutions
  are in [epics-and-conflicts.md](epics-and-conflicts.md). Follow-up interviews are still pending.

## Functional requirements

### FR-01 Login and access control · Must
- **FR-01.1** The system shall let users log in and log out.
- **FR-01.2** Users shall only see information and perform actions their permissions allow, including changing an incident's status.
- **FR-01.3** Application roles (Admin, Engineer, Viewer) shall be separate from incident roles (Lead, Comms, Ops; D39).

### FR-02 Incident creation · Must
- **FR-02.1** Authorized users shall be able to create an incident with a title, description, affected service, and severity.
- **FR-02.2** The system shall generate a unique ID, record the reporter and creation time, and set the status to OPEN.
- **FR-02.3** The system shall record where each incident came from: an alert, a manual report, or a customer report.

### FR-03 Alert intake and grouping · Must
- **FR-03.1** The system shall check the source and required fields of each incoming alert and reject invalid ones.
- **FR-03.2** Receiving the same event again shall not create another alert or incident.
- **FR-03.3** A new alert related to an active incident shall be linked to it using the configured matching rules (DR-08).
- **FR-03.4** If no matching incident is found, the system shall create a new incident.

### FR-04 Severity and priority · Must
- **FR-04.1** The system shall support SEV1, SEV2, SEV3, and SEV4 (DR-01).
- **FR-04.2** Authorized users shall be able to change severity, with the reason recorded.
- **FR-04.3** The incident board shall sort by severity and by time remaining before an SLA deadline.

### FR-05 Incident assignment · Must
- **FR-05.1** The system shall assign a new incident to the service's on-call responder, or to its fallback contact.
- **FR-05.2** Authorized users shall be able to reassign an incident to another person. Reassigning to a team is Could.
- **FR-05.3** The system shall show the current owner and record who changed the assignment, when, and from whom to whom.
- **FR-05.4** Authorized users shall be able to assign and transfer incident roles (Lead, Comms, Ops; D39), with each change recorded in the timeline.
- **FR-05.5** If one person holds more than one incident role on a SEV1 or SEV2 incident, the system shall show a warning but allow it (C3).

### FR-06 Incident status · Must
- **FR-06.1** The system shall support OPEN, ACKNOWLEDGED, INVESTIGATING, IDENTIFIED, MONITORING, RESOLVED, and CLOSED.
- **FR-06.2** *Merged into FR-01.2.*
- **FR-06.3** The system shall allow only the approved transitions (DR-03) and record each accepted change with its actor and time.
- **FR-06.4** An invalid transition shall be rejected without changing the incident.

### FR-07 Notifications and escalation · Must
- **FR-07.1** The system shall notify the assigned responder in the application.
- **FR-07.2** *(Could)* Email or a Slack/Discord webhook may be added as an external notification channel.
- **FR-07.3** If an incident is still unacknowledged at its deadline and another escalation level exists, the system shall notify that level.
- **FR-07.4** An accepted acknowledgement shall cancel pending acknowledgement escalations.
- **FR-07.5** If all levels are exhausted without acknowledgement, the system shall notify the fallback contact once and leave the incident open. It never acknowledges or resolves it automatically.
- **FR-07.6** Escalation deadlines shall be stored persistently and re-evaluated after a restart.
- **FR-07.7** Only an OPEN incident with no accepted acknowledgement shall be escalated, checked against the stored deadline at the moment of escalation.
- **FR-07.8** Notification attempts, failures, and escalation events shall be recorded.
- **FR-07.9** *(Could)* If external delivery is included, failed deliveries shall be visible for retry.

### FR-08 Timeline · Must
- **FR-08.1** The system shall keep a timeline of alerts, status changes, assignments, comments, notifications, escalations, and response actions.
- **FR-08.2** Each entry shall identify what happened, when, and who or what did it.
- **FR-08.3** Corrections shall be added as new entries linked to the original, which stays unchanged.

### FR-09 Collaboration · Must
- **FR-09.1** Authorized users shall be able to add comments, investigation findings, and updates to an incident.
- **FR-09.2** These contributions shall stay in the incident record and follow the incident's access permissions.
- **FR-09.3** *(Should)* Users shall be able to attach links to logs, dashboards, or deployments as evidence.

### FR-10 Root cause and resolution · Must
- **FR-10.1** Responders shall be able to record suspected or confirmed causes, resolution steps, and evidence of recovery.
- **FR-10.2** An incident may be resolved once recovery is verified, even if its root cause is still unknown.
- **FR-10.3** *Merged into FR-11.3.*

### FR-11 Postmortems and action items · Must
- **FR-11.1** Users shall be able to prepare a postmortem with a summary, impact, findings, timeline, resolution, and follow-up actions.
- **FR-11.2** The incident timeline shall be available while the postmortem is being prepared.
- **FR-11.3** Each action item, corrective or preventive, shall have an owner, a due date, and a completion status.
- **FR-11.4** *(Could)* The system may generate a first draft from the incident timeline (AI roadmap feature 5).

### FR-12 Dashboard and reports · Must
- **FR-12.1** The dashboard shall show total, open, and resolved incidents, counts by severity, and average response and resolution times.
- **FR-12.2** Users shall be able to filter by date, severity, status, team, and service, and see each incident's owner and SLA state.
- **FR-12.3** *(Should)* Live updates shall show saved changes without a manual refresh.

### FR-13 Audit log · Must
- **FR-13.1** The system shall record incident changes, permission changes, and changes to service and escalation settings.
- **FR-13.2** Each record shall identify the user or system actor, the action, the time, and the affected item.
- **FR-13.3** Existing audit entries shall not be editable through the application.

### FR-14 Service catalog · Must
- **FR-14.1** Authorized users shall be able to add and update services, their owners, criticality, and response and resolution targets.
- **FR-14.2** *(Could)* Users may record service dependencies to support investigation.

### FR-15 On-call schedules · Must
- **FR-15.1** Authorized users shall be able to assign responders to on-call time slots, using one project time zone.
- **FR-15.2** The system shall select the responder scheduled at the incident's creation time, or the fallback contact if no one is scheduled.
- **FR-15.3** *(Could)* Automatic rotations and temporary overrides may be added.

### FR-16 SLA tracking · Must
- **FR-16.1** The system shall keep separate response and resolution clocks for each incident.
- **FR-16.2** Both clocks shall start at incident creation. The response clock stops at the first accepted acknowledgement; the resolution clock stops at accepted resolution.
- **FR-16.3** The system shall calculate deadlines from the service's targets for the incident's severity, show the time remaining, and mark breaches.
- **FR-16.4** Elapsed time shall count continuously, nights and weekends included (C6).
- **FR-16.5** *(Could)* SLA pauses and business-hours calendars may be added once their rules are agreed.

### FR-17 Previous incident search · Must
- **FR-17.1** Responders shall be able to search previous incidents by keyword and filter by service or severity.
- **FR-17.2** Results shall give access to the previous findings, fixes, and postmortems the user is allowed to see.

### FR-18 AI investigation help · Should
- **FR-18.1** Self-hosted AI shall suggest investigation steps from the incident's details and previous records.
- **FR-18.2** Suggestions shall be labelled AI-generated and name the records they used.
- **FR-18.3** *(Could)* Log and deployment analysis may be added once those data sources exist.
- **FR-18.4** Users shall be able to carry on manually when AI is unavailable.

### FR-19 Review of suggested actions · Should, and Must whenever FR-18 is built
- **FR-19.1** An authorized responder shall review AI suggestions before acting on them.
- **FR-19.2** The system shall record whether each suggestion was accepted or rejected.
- **FR-19.3** *(Must)* The system shall never execute remediation automatically.

### FR-20 Deployment records and DORA metrics · Should
- **FR-20.1** The system shall store deployment details and let users link incidents to deployments.
- **FR-20.2** Reports shall show Deployment Frequency, Lead Time for Changes, Change Failure Rate, and Mean Time to Restore, using agreed definitions.
- **FR-20.3** Missing input data shall be shown as unavailable, not as zero.

### FR-21 External communication · Could (C8 proposes Should)
- **FR-21.1** Customer-facing updates shall be kept separate from internal incident notes.
- **FR-21.2** Only authorized users shall approve and publish external updates.

### FR-22 Service health monitoring · Must (D29)
- **FR-22.1** The system shall check each service's configured health endpoint on a schedule and raise an alert after a configured number of consecutive failures.
- **FR-22.2** When the service recovers, the system shall update the matching alert and add a timeline entry.
- **FR-22.3** Recovery shall not resolve an incident automatically; a person resolves it (DR-11.1).

### FR-23 Personal access tokens · Should
- **FR-23.1** A user shall be able to create named personal access tokens for their own account; each is shown once, and only its hash is stored.
- **FR-23.2** A token shall act as its owner with the owner's current role, and shall stop working when it is revoked, when its owner is disabled, or 90 days after it was created (D38).
- **FR-23.3** A token shall not be able to create or list tokens.
- **FR-23.4** Creating and revoking a token shall be audited, and tokens shall never be written to logs.

### FR-24 Service map and blast radius · Should
- **FR-24.1** The system shall show services as a map, with the dependencies recorded under FR-14.2 and each service's health.
- **FR-24.2** For a service, the system shall list every service that depends on it, directly or through others (its blast radius), each once.
- **FR-24.3** Dependencies shall be shown as possible relationships, not as proof of a shared cause.

### FR-25 Runbooks · Should
- **FR-25.1** Authorized users shall be able to write runbooks for a service as ordered steps; every change is audited.
- **FR-25.2** The incident page shall offer the runbooks of the incident's service.

### FR-26 Logs explorer · Should
- **FR-26.1** Users shall be able to search a service's logs by level, text, and time range. The backend runs every query with its own read token (D34).
- **FR-26.2** One query's time range shall be capped (*proposed:* 24 hours).
- **FR-26.3** If the log store is slow or unavailable, the system shall say so within a timeout, and everything else shall keep working.

### FR-27 Metrics dashboards · Should
- **FR-27.1** For each service, the system shall show request rate, error rate, and latency (p50 and p95) over a chosen time range, queried by the backend (D34).
- **FR-27.2** The incident page shall show the same metrics for the incident's service and time window.
- **FR-27.3** If the metrics store is slow or unavailable, the charts shall say so, and everything else shall keep working.

### FR-28 MCP server · Should
- **FR-28.1** An MCP server shall let AI assistants read incident data through the REST API with the caller's personal access token, so the caller's permissions and the audit log apply.
- **FR-28.2** It shall never change an incident's status, or declare or close an incident. Write tools, when added, shall act as the user and be audited (D38).
- **FR-28.3** The MCP server shall not access the database directly.

## Non-functional requirements

Targets marked *proposed* are starting points, to be confirmed after the first load test.

### NFR-01 Performance
- **NFR-01.1** With 1,000 stored incidents, the incident board and incident detail shall load in under 2 seconds for 95% of requests on the deployed setup. *(proposed)*
- **NFR-01.2** The alert endpoint shall answer in under 1 second for 95% of requests at 10 alerts per second. *(proposed)*

### NFR-02 Availability
- **NFR-02.1** Users shall get a clear error when an operation is unavailable or fails.
- **NFR-02.2** A failed write shall never appear successful.

### NFR-03 Capacity
- **NFR-03.1** The system shall support one organization with at least 10 concurrent users, 50 services, and 10,000 stored incidents. *(proposed)*
- **NFR-03.2** *Moved to TESTING_AND_WORKFLOW.md (how capacity is tested).*

### NFR-04 Security
- **NFR-04.1** Passwords shall be stored as salted hashes, and the server shall check permissions on every protected operation.
- **NFR-04.2** Passwords, API tokens, and webhook secrets shall never appear in logs.
- **NFR-04.3** Outside local development, all client-server traffic shall use HTTPS.
- **NFR-04.4** Login attempts, alert webhooks, and requests made with a personal access token shall be rate-limited per caller. A caller over its limit gets HTTP 429 with a `Retry-After` header (D42). *(proposed)*

### NFR-05 Reliability
- **NFR-05.1** A write shall be reported as successful only after it is saved in the database.
- **NFR-05.2** Saved incidents and escalation deadlines shall survive an application restart.

### NFR-06 Usability
- **NFR-06.1** The incident list shall clearly show severity, status, owner, and SLA state.
- **NFR-06.2** Forms shall identify missing or invalid fields. Severity shall be shown with a label or symbol as well as colour.
- **NFR-06.3** *Moved to TESTING_AND_WORKFLOW.md (usability reviews with users).*

### NFR-07 Maintainability
- **NFR-07.1** Authentication, incident handling, notifications, and reporting shall be separate modules.
- **NFR-07.2** Status, SLA, and escalation rules shall be testable without the UI or the AI service.

### NFR-08 Auditability
- **NFR-08.1** Timeline and audit entries shall include an actor and a timestamp.
- **NFR-08.2** Existing entries shall not be overwritten through the application; corrections refer to the original.
- **NFR-08.3** *Moved to Out of scope.*

### NFR-09 Browser compatibility
- **NFR-09.1** Core workflows shall work in current desktop Chrome and Firefox.
- **NFR-09.2** *Moved to TESTING_AND_WORKFLOW.md (recording tested versions).*

### NFR-10 Backup and recovery
- **NFR-10.1** There shall be a documented manual backup and restore procedure, proven by restoring a test backup.
- **NFR-10.2** *Moved to Out of scope.*

### NFR-11 Safe concurrent updates
- **NFR-11.1** If users acknowledge an incident at the same time, only one initial acknowledgement shall be accepted.
- **NFR-11.2** A conflicting update shall be rejected with a message rather than silently overwrite an accepted change.

### NFR-12 AI failure handling
- **NFR-12.1** AI models shall be self-hosted through Ollama, on a team machine or our own container, with a configurable timeout. Incident data shall not be sent to external AI providers (D28).
- **NFR-12.2** Invalid AI output or AI failure shall not block core incident functions. Structured output shall be validated before use.

### NFR-13 Testing
- **NFR-13.1** *Merged into DR-10.2.*
- **NFR-13.2** *Moved to TESTING_AND_WORKFLOW.md.*
- **NFR-13.3** *Moved to TESTING_AND_WORKFLOW.md.*

### NFR-14 Live updates (applies with FR-12.3)
- **NFR-14.1** Connected boards shall show saved changes without a manual refresh.
- **NFR-14.2** If the connection drops, the screen shall say so and refresh its data after reconnecting.

## Domain requirements

### DR-01 Severity levels
SEV1 critical service or business impact · SEV2 high impact needing urgent attention · SEV3 moderate impact · SEV4 low impact.
- **DR-01.1** Each level shall have documented examples and impact criteria.
- **DR-01.2** Severity describes impact. Response priority may also consider the time left before an SLA deadline.

### DR-02 Incident responsibilities
- **DR-02.1** The Lead coordinates the response; Ops investigate and fix; Comms manages updates. The timeline records events, so there is no Scribe role (D39).
- **DR-02.2** One person may hold more than one responsibility (see FR-05.5 for SEV1 and SEV2).
- **DR-02.3** Holding an incident responsibility shall not grant system administrator permissions.

### DR-03 Incident lifecycle
- **DR-03.1** The normal flow is OPEN → ACKNOWLEDGED → INVESTIGATING → IDENTIFIED → MONITORING → RESOLVED → CLOSED.
- **DR-03.2** If recovery fails, MONITORING may return to INVESTIGATING.
- **DR-03.3** If recovery is possible without a confirmed cause, INVESTIGATING may move to MONITORING.
- **DR-03.4** No other transition is allowed. Any automatic transition must be listed here first.

### DR-04 SLA timing
- **DR-04.1** to **DR-04.4** *Merged into FR-16.*

### DR-05 Affected services
- **DR-05.1** Each incident shall identify its primary affected service.
- **DR-05.2** Recorded service dependencies indicate possible impact, not a shared root cause.

### DR-06 Postmortem policy
- **DR-06.1** *Merged into FR-11.1.*
- **DR-06.2** An unknown root cause shall be recorded as unknown, not guessed.
- **DR-06.3** SEV1 and SEV2 incidents need a reviewed postmortem before closure, judged by the highest severity the incident reached. It is optional for SEV3 and SEV4 (C4).

### DR-07 Learning from incidents
- **DR-07.1** Reviews shall focus on contributing conditions and corrective actions, not blame.
- **DR-07.2** Management reports shall show team- or service-level trends, never individual rankings.

### DR-08 Grouping alerts
- **DR-08.1** A repeated delivery of the same event is a duplicate. A different alert about the same issue is a related alert.
- **DR-08.2** Alerts shall not be grouped only because they share a service.
- **DR-08.3** Alerts from different services may share an incident only when a responder links them. Automatic cross-service grouping is out of scope (D07).
- **DR-08.4** Adding an alert shall not reset the incident's creation time or SLA clocks.

### DR-09 Acknowledgement and escalation
- **DR-09.1** Acknowledging means someone has accepted responsibility. It does not mean the problem is fixed.
- **DR-09.2** *Merged into FR-07.4.*
- **DR-09.3** Resolution-SLA breach notifications may continue after acknowledgement.
- **DR-09.4** *Merged into FR-07.5.*

### DR-10 Previous fixes and AI suggestions
- **DR-10.1** A previous fix is supporting information, not a guarantee that it suits the current incident.
- **DR-10.2** Status, SLA, and escalation decisions shall come from deterministic rules. AI shall never change status, calculate SLAs, or run fixes.

### DR-11 Recovery and closure
- **DR-11.1** Recovery of one alert shall not resolve an incident while other recovery conditions are unmet.
- **DR-11.2** RESOLVED means recovery is verified. CLOSED means the required review and documentation are complete.
- **DR-11.3** Action items may stay open after closure if they have owners and due dates.

## Out of scope

- Automatic deletion and retention management (was NFR-08.3).
- Automatic backup schedules and guaranteed recovery times (was NFR-10.2).
- Automatic remediation (FR-19.3).
- Automatic grouping of alerts across services (DR-08.3).

## Changes from the submitted version

| ID | Change | Why |
| --- | --- | --- |
| FR-01.2 | Covers status changes | Absorbs FR-06.2 |
| FR-02.3 | **New:** incident source | Survey: support complaints had no route in (epics §4.2) |
| FR-05.4, FR-05.5 | **New:** assign Commander and Scribe; warning when one person holds both on SEV1/SEV2 | US-09 needed an FR; C3 |
| FR-06.2 | Merged into FR-01.2 | Same rule |
| FR-07.5 | Says escalation never acknowledges or resolves | Absorbs DR-09.4 |
| FR-07.7 | Reworded as behaviour | Was written as an implementation step |
| FR-09.3 | **New:** evidence links | Survey: resolvers look at logs first (epics §4.2) |
| FR-23 to FR-28 | **New:** personal access tokens, service map and blast radius, runbooks, logs explorer, metrics dashboards, MCP server | Sprint 4 features that were *New* in FEATURES.md (#149) |
| FR-10.3 | Merged into FR-11.3 | Same as action items |
| FR-11.3 | Covers corrective and preventive actions | Absorbs FR-10.3 |
| FR-18.1 | "Local" → "self-hosted" | D28 |
| FR-20, FR-21 | Deferred → Should and Could | Match the submitted PDF and user stories US-19, US-20 |
| FR-22 | **Should → Must**; recovery never auto-resolves | The project brief lists "monitor service health" (D29) |
| NFR-01, NFR-03 | Measurable, proposed targets | "Shall be measured" can't pass or fail |
| NFR-03.2, 06.3, 09.2, 13.2, 13.3 | Moved to TESTING_AND_WORKFLOW.md | They describe how we test, not the system |
| NFR-08.3, NFR-10.2 | Moved to Out of scope | They were scope notes |
| NFR-12.1 | Self-hosted through Ollama; no external AI providers | D28 |
| NFR-13.1 | Merged into DR-10.2 | Same rule |
| DR-04 | Merged into FR-16 | Restated FR-16 |
| DR-06.1 | Merged into FR-11.1 | Restated FR-11.1 |
| DR-06.3 | SEV1 and SEV2 need a postmortem | C4 |
| DR-08.3 | Cross-service grouping only by a responder | D07 |
| DR-09.2, DR-09.4 | Merged into FR-07.4 and FR-07.5 | Restated them |

**Counts:** 22 FRs with 75 active sub-requirements, 14 NFRs with 24, 11 DRs with 26.
