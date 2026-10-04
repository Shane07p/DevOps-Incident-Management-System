# Requirements Summary

## How to use this file

This is a shorter planning summary, not a replacement for the existing detailed requirements submission. FR references below reuse its feature IDs. Update the detailed requirements after the team accepts any scope changes; do not renumber existing test references silently.

User stories live in [user-stories.md](user-stories.md). Epics, survey findings, and conflict resolutions live in [epics-and-conflicts.md](epics-and-conflicts.md).

No stakeholder elicitation has been completed. These priorities and rules are proposals. Do not add invented findings, source labels, or claims of stakeholder agreement.

## Functional scope

Must = first version. Should = useful next addition. Could = optional if time remains. Deferred = outside this implementation plan for now.

| Existing ID | Priority | Simple requirement for this plan |
| --- | --- | --- |
| FR-01 | Must | Login/logout and Admin, Engineer, Viewer permissions |
| FR-02 | Must | Create an incident with title, description, service, and severity; record creator and time |
| FR-03 | Must | Accept authenticated alerts; reject invalid events; deduplicate deliveries; group only by an explicit source/service correlation key |
| FR-04 | Must | Support SEV1–4, record reasons for severity changes, and sort the board by severity or SLA deadline |
| FR-05 | Must | Assign a responder from on-call or fallback; allow reassignment to another individual and record it |
| FR-06 | Must | Enforce the seven statuses and the additional transitions described in the design |
| FR-07 | Must | Send in-app notifications; persist escalation deadlines; stop pending escalation on acknowledgement; notify fallback once when steps are exhausted; escalation alone does not reassign |
| FR-08 | Must | Record actions in a timeline; add attributed corrections without overwriting earlier entries |
| FR-09 | Must | Allow Engineers and Admins to add comments and investigation findings |
| FR-10 | Must | Record recovery evidence and the fix; allow the root cause to remain unknown |
| FR-11 | Must | Save a basic postmortem and action items with an owner and completion state |
| FR-12 | Must | Show a filterable dashboard and simple counts by service, severity, and status; manual refresh is sufficient |
| FR-13 | Must | Record who changed access, service settings, or escalation configuration and when |
| FR-14 | Must | Manage services, owner teams, fallback contacts, and severity-based SLA targets |
| FR-15 | Must | Enter on-call time slots manually; reject overlapping slots for the same service |
| FR-16 | Must | Track separate response and resolution clocks using fixed targets copied at creation |
| FR-17 | Must | Search previous incidents by text and service, and read their recorded fixes |
| FR-18 | Should | Suggest investigation steps using previous incidents through a local model |
| FR-19 | Should, with FR-18 | Let users review suggestions; perform any chosen action through the normal authorized controls |
| FR-20 | Deferred | Deployment records and full DORA reporting |
| FR-21 | Deferred | Public status page and external customer updates |
| FR-22 | Should | Basic health checks after the core works |
| FR-07 extension | Could | One external notification channel |
| FR-12 extension | Could | Live updates using SSE |

Do not interpret the table as a commitment to every sub-item in the older FR groups. In particular, team reassignment, rotations, advanced SLA rules, cross-service correlation, and external reporting are reduced or deferred here.

## Non-functional requirements

These short labels belong to this planning summary; they do not renumber the NFRs in the detailed document.

| Area | Requirement and how we check it |
| --- | --- |
| Security | Enforce permissions on the backend, hash passwords, authenticate alerts, and keep secrets out of the repository. Test denied requests as well as allowed ones. |
| Reliability | Keep incident changes and their timeline, notification, and escalation effects in one transaction. Verify that a failed operation leaves no partial changes. |
| Restart behavior | Store escalation deadlines in PostgreSQL. Restart the backend during an unanswered incident and check that escalation continues without replaying completed steps. |
| Concurrency | Accept only one initial acknowledgement and prevent duplicate records from concurrent copies of an alert. Test with overlapping requests. |
| Usability | Display the current owner, status, severity, and SLA state clearly. Show a useful message when an action fails. Check the complete workflow manually. |
| Performance | Measure the board, incident detail, and alert endpoint with a documented sample workload. Use the results and elicitation to agree on targets; do not claim untested capacity or uptime. |
| Maintainability | Keep business rules testable, schema changes in Flyway, and important API behavior documented. CI builds the application and runs selected automated tests. |
| AI fallback | If AI is added, a timeout or invalid response must leave the manual workflow usable. Test using a stub. |

## Domain rules that must stay clear

1. SEV1 is most severe; SEV4 is least severe. Severity controls priority, not whether unrelated incidents are merged.
2. A duplicate delivery is the same source event arriving again. A related alert is a different event tied to the same active issue by an explicit key.
3. Sharing a service does not by itself make two alerts the same issue. Different services are handled as separate incidents in the initial grouping implementation.
4. First acknowledgement stops the response clock and acknowledgement escalation. It does not stop the resolution clock.
5. Exhausting escalation steps does not acknowledge or resolve the incident.
6. SLA targets are fixed when the incident is created. New alerts and reassignment do not reset elapsed time.
7. RESOLVED means recovery is recorded; CLOSED means the basic postmortem has also been reviewed. Outstanding action items remain visible.
8. AI proposes explanations or steps. Deterministic code handles status, timing, and escalation.
