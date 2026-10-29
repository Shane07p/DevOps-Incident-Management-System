# User Stories: DevOps Incident Management System

**Group 2** | Requirements and Process Modeling

## Format

Each story follows this structure:

- **Story:** As a `<stakeholder>`, I want `<goal>`, so that `<benefit>`.
- **Priority:** Must / Should / Could (MoSCoW, provisional)
- **Traces to:** related FR / NFR / DR IDs
- **Acceptance criteria:** Given / When / Then conditions, each mapping to a future test case

## Summary

| ID | Stakeholder | Story | Priority | Traces to |
|---|---|---|---|---|
| US-01 | Platform Admin | Manage users and roles | Must | FR-01, DR-02.3, NFR-04.1 |
| US-02 | Platform Admin | Register monitoring tools | Must | FR-03.1, FR-22, NFR-04.2 |
| US-03 | On-Call Engineer | Create incident manually | Must | FR-02 |
| US-04 | On-Call Engineer | Handle duplicate and related alerts | Must | FR-03.2 to FR-03.4, DR-08 |
| US-05 | Service Owner | Register a service | Must | FR-14 |
| US-06 | Service Owner | Set on-call schedule | Must | FR-05.1, FR-15 |
| US-07 | Service Owner | Escalate unacknowledged incidents | Must | FR-07, DR-09 |
| US-08 | On-Call Engineer | Acknowledge incident | Must | FR-06, FR-07.4, FR-16.2, NFR-11 |
| US-09 | Incident Commander | Reassign and assign roles | Must | FR-05.2, FR-05.3, DR-02 |
| US-10 | Incident Commander | Update status and severity | Must | FR-04, FR-06, DR-03 |
| US-11 | Scribe | Maintain incident timeline | Must | FR-08, FR-09 |
| US-12 | Service Owner | Track SLAs | Must | FR-16, DR-04 |
| US-13 | On-Call Engineer | Resolve incident | Must | FR-10, DR-11 |
| US-14 | Incident Commander | Write postmortem | Must | FR-11, DR-06 |
| US-15 | On-Call Engineer | Search past incidents | Must | FR-17 |
| US-16 | Engineering Manager | View dashboard | Must | FR-12, DR-07.2 |
| US-17 | Compliance / Audit | View audit log | Must | FR-13 |
| US-18 | On-Call Engineer | AI investigation help | Should | FR-18, FR-19, DR-10.2 |
| US-19 | Engineering Manager | Deployment tracking and DORA | Should | FR-20 |
| US-20 | Communications Lead | Publish customer updates | Could | FR-21 |

**Totals:** 17 Must, 2 Should, 1 Could

---

## US-01: Manage users and roles

**Story:** As a **Platform Admin**, I want to manage users, roles, and permissions, so that people only see and do what they're allowed to.

**Priority:** Must
**Traces to:** FR-01, DR-02.3, NFR-04.1

**Acceptance criteria:**
- Given I create a user with a role, then they can log in and access only what that role allows.
- Given a user attempts an action without permission, then the server rejects it.
- Given a user is Commander on an incident, then they do not gain admin permissions.
- Then role and permission changes are recorded in the audit log.

---

## US-02: Register monitoring tools

**Story:** As a **Platform Admin**, I want to register monitoring tools that detect problems in our services, so that their alerts create incidents and only trusted tools can send alerts.

**Priority:** Must (health checks: Should)
**Traces to:** FR-03.1, FR-22, NFR-04.2

**Acceptance criteria:**
- Given I register a tool, then a token is generated and shown once.
- Given an alert with a valid token and required fields, then it is accepted.
- Given an invalid or revoked token, or missing fields, then it is rejected and nothing is created.
- Then tokens never appear in logs.
- *Should:* Given a health check fails a configured number of times, then an alert is raised; on recovery, the alert is updated.

---

## US-03: Create incident manually

**Story:** As an **On-Call Engineer**, I want to create an incident manually, so that problems reported by people are also tracked.

**Priority:** Must
**Traces to:** FR-02

**Acceptance criteria:**
- Given I submit title, description, service, and severity, then an incident is created with a unique ID, reporter, time, and status OPEN.
- Given a required field is missing, then the form shows which one and nothing is created.

---

## US-04: Handle duplicate and related alerts

**Story:** As an **On-Call Engineer**, I want duplicate alerts ignored and related alerts grouped, so that one problem appears as one incident.

**Priority:** Must
**Traces to:** FR-03.2 to FR-03.4, DR-08

**Acceptance criteria:**
- Given the same event arrives again, then it is ignored.
- Given a related alert matches an active incident, then it is linked and added to the timeline.
- Then linking never resets the incident's creation time or SLA clocks.
- Given no match, then a new incident is created.

---

## US-05: Register a service

**Story:** As a **Service Owner**, I want to register my service with its owner, criticality, and targets, so that incidents are handled according to its importance.

**Priority:** Must
**Traces to:** FR-14

**Acceptance criteria:**
- Given I add a service with owner, criticality, and response/resolution targets per severity, then it can be selected for incidents.
- Given I update targets, then the change is recorded in the audit log.

---

## US-06: Set on-call schedule

**Story:** As a **Service Owner**, I want to set my service's on-call schedule, so that new incidents are auto-assigned to the right responder.

**Priority:** Must
**Traces to:** FR-05.1, FR-15

**Acceptance criteria:**
- Given I assign responders to time slots, then slots use the project time zone.
- Given an incident is created, then it is assigned to the responder scheduled at creation time and they are notified in-app.
- Given no one is scheduled, then it is assigned to the fallback contact.
- Then the assignment is recorded in the timeline.

---

## US-07: Escalate unacknowledged incidents

**Story:** As a **Service Owner**, I want unacknowledged incidents to escalate, so that no incident is ignored.

**Priority:** Must
**Traces to:** FR-07, DR-09

**Acceptance criteria:**
- Given an OPEN, unacknowledged incident passes its deadline, then the next level is notified.
- Given all levels are exhausted, then the fallback is notified and the incident stays OPEN.
- Given the application restarts, then stored deadlines are re-checked and overdue incidents escalate once.
- Then every notification and escalation is recorded in the timeline.

---

## US-08: Acknowledge incident

**Story:** As an **On-Call Engineer**, I want to acknowledge an incident, so that others know I've taken responsibility and escalation stops.

**Priority:** Must
**Traces to:** FR-06, FR-07.4, FR-16.2, NFR-11

**Acceptance criteria:**
- Given an OPEN incident, when I acknowledge, then it becomes ACKNOWLEDGED with my name and time.
- Then the response clock stops and pending escalations are cancelled.
- Given two users acknowledge at once, then only one is accepted and the other sees a conflict message.

---

## US-09: Reassign and assign roles

**Story:** As an **Incident Commander**, I want to reassign the incident and assign roles like Commander, so that the right people are working on it.

**Priority:** Must
**Traces to:** FR-05.2, FR-05.3, DR-02 (*Commander assignment needs a new FR*)

**Acceptance criteria:**
- Given I reassign, then the owner updates, the new owner is notified, and the timeline records from whom, to whom, by whom, and when.
- Then reassignment does not change the Commander or restart the response clock.
- Given I assign or transfer Commander, then it is recorded in the timeline.

---

## US-10: Update status and severity

**Story:** As an **Incident Commander**, I want to update status and severity, so that everyone knows where the incident stands.

**Priority:** Must
**Traces to:** FR-04, FR-06, DR-03

**Acceptance criteria:**
- Given a valid transition, then status updates and is recorded with actor and time.
- Given an invalid transition, then it is rejected and the incident is unchanged.
- Given I change severity, then a reason is required, and the SLA targets copied at creation stay unchanged (D09/D10).
- Then the board can be sorted by severity and time remaining.

---

## US-11: Maintain incident timeline

**Story:** As a **Scribe**, I want to record actions, decisions, and corrections on a timeline, so that there's an accurate history of the incident.

**Priority:** Must
**Traces to:** FR-08, FR-09

**Acceptance criteria:**
- Then system events (alerts, status changes, assignments, notifications) are added automatically.
- Given I add a comment, action, decision, or finding, then it shows my name and time.
- Given I correct an entry, then a new linked entry is added and the original stays unchanged.

---

## US-12: Track SLAs

**Story:** As a **Service Owner**, I want to see response and resolution clocks and breaches, so that I know if targets are being met.

**Priority:** Must
**Traces to:** FR-16, DR-04

**Acceptance criteria:**
- Given an incident is created, then both clocks start with deadlines from service targets and severity.
- Then acknowledgement stops only the response clock; resolution stops the resolution clock.
- Then remaining time is shown and breaches are clearly marked.

---

## US-13: Resolve incident

**Story:** As an **On-Call Engineer**, I want to record the cause and resolution steps and resolve the incident, so that recovery is documented even if the root cause is unknown.

**Priority:** Must
**Traces to:** FR-10, DR-11

**Acceptance criteria:**
- Given I record cause, steps, and recovery evidence, then they are saved to the incident.
- Given recovery is verified but cause is unknown, then I can resolve with cause marked unknown.
- Given one alert recovers while others are still firing, then the incident is not auto-resolved.

---

## US-14: Write postmortem

**Story:** As an **Incident Commander**, I want to prepare a postmortem with action items, so that the team learns and prevents repeats.

**Priority:** Must
**Traces to:** FR-11, DR-06

**Acceptance criteria:**
- Given I create a postmortem, then it includes summary, impact, findings, timeline, resolution, and follow-ups, with the timeline visible while writing.
- Then each action item has an owner, due date, and status.
- Then action items can stay open after the incident is CLOSED.
- *Could:* a draft is generated from the timeline.

---

## US-15: Search past incidents

**Story:** As an **On-Call Engineer**, I want to search previous incidents, so that I can reuse past findings and fixes.

**Priority:** Must
**Traces to:** FR-17

**Acceptance criteria:**
- Given I search by keyword and filter by service or severity, then matching incidents are listed with their findings, fixes, and postmortems.
- Then only incidents I'm allowed to view appear.

---

## US-16: View dashboard

**Story:** As an **Engineering Manager**, I want a dashboard with filters and trends, so that I can spot problem areas.

**Priority:** Must (live updates: Should)
**Traces to:** FR-12, DR-07.2

**Acceptance criteria:**
- Then it shows total, open, and resolved counts, severity counts, and average response and resolution times.
- Given I filter by date, severity, status, team, or service, then results update.
- Then metrics are team- or service-level, not individual rankings.
- *Should:* changes appear without refresh; a dropped connection is shown.

---

## US-17: View audit log

**Story:** As a **Compliance / Audit** reviewer, I want a tamper-proof audit log, so that I can verify what happened and who changed what.

**Priority:** Must
**Traces to:** FR-13

**Acceptance criteria:**
- Then incident, permission, service, and escalation changes are recorded with actor, action, time, and item.
- Then no one, including admins, can edit or delete entries through the app.

---

## US-18: AI investigation help

**Story:** As an **On-Call Engineer**, I want AI suggestions I review before acting, so that I can investigate faster without losing control.

**Priority:** Should
**Traces to:** FR-18, FR-19, DR-10.2

**Acceptance criteria:**
- Then suggestions are labelled AI-generated and list the records used.
- Then I must accept or reject each suggestion, and the decision is recorded.
- Then AI never changes status, calculates SLAs, or runs fixes.
- Given AI is down or times out, then all core functions still work.

---

## US-19: Deployment tracking and DORA

**Story:** As an **Engineering Manager**, I want to link incidents to deployments and see DORA metrics, so that I can see how releases affect stability.

**Priority:** Should
**Traces to:** FR-20

**Acceptance criteria:**
- Given deployments are recorded, then incidents can be linked to them.
- Given data is missing, then the metric shows "unavailable," not zero.

---

## US-20: Publish customer updates

**Story:** As a **Communications Lead**, I want to publish approved updates separate from internal notes, so that customers are informed without leaking internal details.

**Priority:** Could
**Traces to:** FR-21

**Acceptance criteria:**
- Then public updates are stored separately from internal notes.
- Then only authorized users can approve and publish.
