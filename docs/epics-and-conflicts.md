# Epics and Stakeholder Conflicts

**Group 2** | DevOps Incident Management System

## 1. Epics

Eight epics cover all user stories. They are listed in build order: each epic depends only on the ones before it, except E8, which runs through every sprint.

| Epic | Goal | User stories | Priority |
|---|---|---|---|
| **E1: Setup** | Get users, tools, services, and schedules ready | US-01 Users and roles<br>US-02 Register monitoring tools<br>US-05 Register a service<br>US-06 Set on-call schedule | Must |
| **E2: Incident Intake** | Get incidents into the system | US-03 Create incident manually<br>US-04 Handle duplicate and related alerts | Must |
| **E4a: Recording Foundation** | Record every action from the first incident (built alongside E2) | US-11 Timeline (append-only entries, linked corrections)<br>US-17 Audit log (recording)<br>US-12 SLA tracking (clocks start at creation, stop on acknowledgement and resolution) | Must |
| **E3: Incident Response** | Make sure someone responds and manages the incident | US-07 Escalation<br>US-08 Acknowledge<br>US-09 Reassign and assign roles<br>US-10 Update status and severity<br>US-22 Comments, findings, and evidence links<br>US-23 Notification delivery record | Must |
| **E4b: Tracking Views** | Show the history and targets | US-11 Timeline view<br>US-12 Remaining time and breach indicators<br>US-17 Audit log view | Must |
| **E5: Resolution and Learning** | Close incidents properly and learn from them | US-13 Resolve incident<br>US-14 Postmortem<br>US-15 Search past incidents | Must |
| **E6: Insights** | Show trends to managers | US-16 Dashboard<br>US-19 DORA metrics | Must / Should |
| **E7: Extensions** | Optional features after the core works | US-18 AI help<br>US-20 Customer updates<br>US-21 Automated health checks<br>US-24 Major-incident declaration | Should / Could |
| **E8: Cross-cutting Quality** | Keep the core secure, durable, and testable | TS-01 Security baseline<br>TS-02 Restart-safe escalation deadlines<br>TS-03 Safe concurrent updates<br>TS-04 Backup and restore test<br>TS-05 Deterministic rule tests and mutation testing<br>TS-06 Chrome and Firefox check | Must (TS-01, 02, 03, 05)<br>Should (TS-04, 06) |

### 1.1 Additional stories

| ID | Story | Source | Priority |
|---|---|---|---|
| US-21 | Automated health checks raise an alert after a failure threshold and update it on recovery. Automatic resolution needs an explicit recovery rule | FR-22 | Should |
| US-22 | Add comments, findings, and links to logs, dashboards, or deployments on an incident | FR-09, FR-10.1 | Must (comments)<br>Should (evidence links) |
| US-23 | View every notification attempt, failure, and escalation event | FR-07.8, FR-07.9 | Must (recording)<br>Could (retry view) |
| US-24 | After the last escalation level and the fallback notification, flag the incident as unowned and let a person declare a major incident | FR-07.5, DR-09.4 | Could |

The technical stories map to NFRs as follows: TS-01 to NFR-04, TS-02 to NFR-05 and FR-07.6, TS-03 to NFR-11, TS-04 to NFR-10, TS-05 to NFR-13, TS-06 to NFR-09.

### 1.2 Dependencies and milestones

The write side of the timeline, audit log, and SLA clocks must be built with E2, not after E3. Creating an incident starts both SLA clocks (FR-16.2) and must produce a timeline entry (FR-08.1) and an audit entry (FR-13.1). Acknowledgement stops the response clock (FR-16.2), and assignment changes must record who changed what and when (FR-05.3). Only the screens for these can wait until E4b.

| Milestone | Epics | What works |
|---|---|---|
| **M1** | E1, E2, E4a, E3 | An alert comes in, an incident is created, assigned, escalated, and acknowledged, with a timeline, audit trail, and running SLA clocks |
| **M2** | M1 + E4b, E5 | Full lifecycle to CLOSED, including postmortem and past-incident search |
| **M3** | M2 + E6 | Dashboard and reports for managers |
| **M4** | M3 + E7 | Optional extensions |

E8 is built into each milestone rather than added at the end.

### 1.3 Requirement coverage

| Requirements | Covered by |
|---|---|
| FR-01 | US-01 |
| FR-02 | US-03 |
| FR-03 | US-02, US-04 |
| FR-04, FR-06 | US-10 |
| FR-05 | US-09 |
| FR-07 | US-07, US-08, US-23 |
| FR-08 | US-11 |
| FR-09 | US-22 |
| FR-10 | US-13, US-22 |
| FR-11 | US-14 |
| FR-12 | US-16 |
| FR-13 | US-17 |
| FR-14, FR-15 | US-05, US-06 |
| FR-16 | US-12 |
| FR-17 | US-15 |
| FR-18, FR-19 | US-18 |
| FR-20 | US-19 (includes deployment records, FR-20.1) |
| FR-21 | US-20 |
| FR-22 | US-21 |
| NFR-04, 05, 09, 10, 11, 13 | E8 |
| NFR-08 | E4a |
| NFR-12 | US-18 |
| NFR-14 | Later sprint (see C9) |
| NFR-06, NFR-07 | Definition of done for every story |

## 2. Survey Overview

- **Responses:** 24 (16 undergraduates in 3rd/4th year, 5 in 1st/2nd year, 2 working professionals, 1 postgraduate)
- **By role:** Resolver 5, Manager/Reviewer 4, Incident Lead 3, User 3, Scribe 3, Service Owner 3, Support 2, Tool Admin 1, Communications 0
- **Experience:** mostly college projects (13); 7 had no direct experience

**Limitation:** each role-specific question was answered by only 1 to 5 people, mostly students. Findings below are early signals, not final agreements. They should be confirmed in follow-up interviews (4 respondents shared contact details).

Additional points to keep in mind when reading the results:

- Everyone is from one university, and few respondents have run a real on-call rotation.
- Respondents chose their own role, so a student who led a hackathon team may have picked "Incident Lead".
- With 3 respondents in a role, a "2 of 3" majority is decided by a single person.
- Key conflicts (C1 to C9) should be cross-tabulated by experience level, with answers from respondents with no direct experience reported separately.

## 3. Conflicts and Proposed Resolutions

### 3.1 Conflicts from the survey

| # | Conflict | Survey evidence | Proposed resolution | Affects | Confidence |
|---|---|---|---|---|---|
| C1 | **Individual vs team-level reports** | Reviewers: 3 of 4 want both team and individual metrics; 1 wants team-only. SRS says team-level by default. | Team-level reports are the default. Individual figures (for example incidents handled and time to acknowledge) are visible to the person concerned and, with an explicit manager permission, to managers. They are shown per person with no rankings or side-by-side comparisons, and manager access is written to the audit log. | DR-07.2, US-16 | Low (n = 4) |
| C2 | **AI suggestions: review vs apply directly** | Resolvers: 3 of 5 would review first; 2 would apply directly. | Keep human review mandatory, but make it one click (Accept / Reject). The evidence a suggestion cites is shown next to the buttons, every decision is logged, and Accept records approval only. It never runs a remediation. | FR-19, US-18 | Low (n = 5) |
| C3 | **Can one person lead and take notes?** | Incident Leads: 2 of 3 say only for minor problems; 1 says yes. | Allow it for SEV3 and SEV4. For SEV1 and SEV2, show a warning recommending a separate Scribe, but do not block it. The same warning appears if severity is raised to SEV1 or SEV2 while one person holds both roles, and the choice is recorded in the timeline. | DR-02.2, US-09 | Low (n = 3) |
| C4 | **Which incidents need a postmortem?** | Incident Leads: 2 of 3 say critical and high-impact only; 1 says all. | Postmortem required before closure for SEV1 and SEV2, optional for SEV3 and SEV4. The rule uses the highest severity the incident reached, so a later downgrade cannot skip it. | DR-06.3, US-14 | Low (n = 3) |
| C5 | **How often to update customers** | Users: 2 of 3 want updates only when something changes; 1 wants every 30 minutes. | On every status change, prompt the Communications owner with a pre-filled draft. Nothing is published until an authorized user approves it (FR-21.2). For SEV1, remind them if no update has gone out for 30 minutes. | FR-21, US-20 | Low (n = 3) |
| C6 | **Do nights and weekends count toward SLAs?** | Service Owners: 2 of 3 say count all hours; 1 says it depends on severity. | Count all hours in the first version (simple and matches the majority). Severity-based business hours stays a Could. | FR-16.4, FR-16.5, US-12 | Low (n = 3) |
| C7 | **Response time targets differ** | Critical: 2 of 3 say within 15 minutes, 1 says within 1 hour. Minor: 2 of 3 say within 1 day, 1 says within 3 days. | Targets are configurable per service. Defaults: SEV1 respond within 15 minutes, SEV2 within 1 hour, SEV3 within 4 hours, SEV4 within 1 day. SEV2 and SEV3 are proposed values to confirm in interviews. Resolution targets were not covered by the survey, so the service owner sets them before breaches are shown. | FR-14.1, FR-16.3, US-05 | Low (n = 3) |
| C8 | **Customer updates rated important, but scoped as Could** | 18 of 24 rated public status updates somewhat or very important. FR-21 was Could. | Raise FR-21 to Should, close to the 19 of 24 who rated escalation important. Build a minimal version after E1 to E5 (an approved update shown on a read-only page). Channels, subscriptions, and templates stay Could. | FR-21, US-20 | Medium (n = 24) |
| C9 | **Reviewers want real-time dashboards, but live updates are Should** | All 4 reviewers chose a live (real-time) dashboard. | Dashboard shows fresh data on page load in the first version. Live push updates come in a later sprint. | FR-12.3, NFR-14, US-16 | Low (n = 4) |

### 3.2 Conflicts from the SRS and process review

These have no survey evidence yet.

| # | Conflict | Basis | Proposed resolution | Affects |
|---|---|---|---|---|
| C10 | **Alert frequency vs alert noise** | Listed as a conflict in the SRS. Resolver answers on alert volume and false alarms are still to be added. | Store every alert but page once per incident. Link repeats to the existing incident and notify again only when severity rises or a new kind of alert joins. Show the alert count on the incident, and make matching rules configurable. | FR-03, DR-08, US-04 |
| C11 | **Timely public updates vs approval overhead** | FR-21.2 requires approval; no Communications Lead responses. | Pre-written templates per severity, a named backup approver for each shift, and the SEV1 reminder from C5. | FR-21.2, US-20 |
| C12 | **Append-only records vs removing sensitive data pasted by mistake** | NFR-04.2 (no secrets in logs) against FR-08.3, FR-13.3, and NFR-08.2 (entries are never edited). | No deletes. An admin "redact" action hides the content in every view and keeps the entry ID, actor, time, and a redaction record with a reason. Could. | NFR-04.2, NFR-08.2, US-11, US-17 |
| C13 | **Stop at the fallback contact vs keep escalating** | FR-07.5 and DR-09.4: the last level never auto-acknowledges. | After the fallback notification, repeat it at a set interval and flag the incident as unowned on the board and dashboard. A person may declare a major incident (US-24). Never auto-acknowledge or auto-resolve. | FR-07.5, DR-09.4, US-07, US-24 |

## 4. Findings

### 4.1 Findings That Support the Current Requirements

| Finding | Survey evidence | Supports |
|---|---|---|
| Resolve once service works, even if cause unknown | 3 of 3 Incident Leads | FR-10.2, US-13 |
| Keep original note, add linked correction | 3 of 3 Scribes | FR-08.3, US-11 |
| Automatic recording of routine events is helpful | Scribes rated 5, 5, 4 out of 5 | FR-08.1, US-11 |
| Notes need who, what, when, and source | 2 of 3 Scribes chose all four | FR-08.2, US-11 |
| Searching past incidents is useful | Resolvers rated 5, 5, 5, 5, 4 out of 5 | FR-17, US-15 |
| Resolvers check logs and recent deployments first | 4 of 5 check logs; 3 of 5 check deployments | FR-20, US-19 |
| Escalation when nobody responds is important | 19 of 24 rated it somewhat or very important | FR-07, US-07 |
| Response and resolution time tracking is important | 22 of 24 rated it somewhat or very important | FR-16, US-12 |

### 4.2 Findings That Add to the Requirements

| Finding | Survey evidence | Gap | Change |
|---|---|---|---|
| Resolvers look at logs first | 4 of 5 | Log analysis is only a Could (FR-18.3), and nothing lets a responder link logs to an incident | Evidence links on incidents (US-22). No log ingestion needed |
| No defined route from user complaints to the team | Support: 2 responses | FR-02 does not record where an incident came from | Add a source field at creation (alert, manual, customer report) |
| Public updates are widely wanted | 18 of 24 | FR-21 was Could | See C8 |

## 5. Gaps Still Open

- **Communications Lead:** no responses to the questions on approval, update frequency, and separating internal and public information. Needs an interview.
- **Platform Admin:** only 1 response. Access levels, role separation, and integration priority need more input.
- **Support:** only 2 responses, with no clear process for how user complaints reach the team.
- **Coordination and recording challenges:** the open-text questions on these had no answers.
- **Resolution targets and SEV2/SEV3 defaults:** not covered by the survey (C7).
- **Severity examples (DR-01.1):** the Service Owner answers on what makes a problem critical still need to be turned into severity definitions.
- **Survey results still to be incorporated:** Resolver alert volume and false-alarm share (C10), how Incident Leads track who is doing what, Manager top measures and reporting pain points, Support time-to-awareness and auto-flagging, User channel and content preferences, and the final-grid ratings for duplicate grouping, timeline, AI suggestions, and dashboards.
- **Document and API analysis:** Compliance/Audit and External Systems are covered by document analysis, not the survey. Alert fields, authentication, duplicate handling, and audit needs are not yet confirmed.
- **Story overlap:** confirm that US-22 and US-23 do not duplicate US-11 and US-07.

### 5.1 Follow-up Interviews

| Priority | Who | Topics | Confirms |
|---|---|---|---|
| 1 | Communications owner (for example a club or fest communications lead) | Who approves updates, update frequency, internal vs public separation | C5, C8, C11 |
| 2 | Someone who has set up access and alerting tools (for example a senior with a DevOps internship) | Roles, permissions, integrations, on-call assignment | US-01, US-02, US-06 |
| 3 | Support or help-desk person | How complaints reach the team, what they need during an outage | Support gap, FR-21 |
| 4 | The 4 respondents who shared contact details, matched to their role | The questions that had only 3 responses | C3, C4, C6, C7 |
| 5 | The 2 working professionals and anyone with real on-call experience | Realism of the proposals, especially C1, C2, C7, C13 | Section 3 |
| 6 | One manager or reviewer | Individual vs team views, dashboard refresh | C1, C9 |

Interviews should take about 15 minutes and use a short scenario ("the app went down at 2 am, walk me through what happens") rather than asking respondents to choose between options.

### 5.2 Decisions for the Team

1. C1: may managers see permission-gated individual figures, with no rankings and audited access?
2. C8: raise FR-21 and US-20 from Could to Should with a minimal first version?
3. Adopt the E4a / E4b split so the recording side of timeline, audit, and SLA ships with E2?
4. Which E8 stories go into the first sprint?
5. Confirm the SEV2 (1 hour) and SEV3 (4 hours) response defaults, or choose others.
6. Keep automated health checks (US-21) as Should, or defer to Could?
7. Keep C12, C13, and US-24 as Could, or drop them?

### 5.3 SRS Updates That Follow

| Section | Update |
|---|---|
| FR-02.1 | Add an incident source field (alert, manual, customer report) |
| FR-09, FR-10.1 | Add links to logs, dashboards, and deployments on an incident |
| FR-07.5 | Repeat the fallback notification at an interval and flag the incident as unowned |
| FR-14.1, FR-16.3 | Resolution targets set by the service owner before breaches are shown |
| FR-21.2 | If C8 is accepted: raise the priority and add templates and a backup approver |
| DR-01.1 | Add severity examples from the Service Owner answers |
| DR-06.3 | Set to SEV1 and SEV2, based on the highest severity reached |
| DR-07.2 | State that managers see individual figures only with explicit permission, without rankings |
