# Epics and Stakeholder Conflicts

**Group 2** | DevOps Incident Management System

## 1. Epics

Seven epics cover all 20 user stories. They are listed in build order: each epic depends only on the ones before it.

| Epic | Goal | User stories | Priority |
|---|---|---|---|
| **E1: Setup** | Get users, tools, services, and schedules ready | US-01 Users and roles<br>US-02 Register monitoring tools<br>US-05 Register a service<br>US-06 Set on-call schedule | Must |
| **E2: Incident Intake** | Get incidents into the system | US-03 Create incident manually<br>US-04 Handle duplicate and related alerts | Must |
| **E3: Incident Response** | Make sure someone responds and manages the incident | US-07 Escalation<br>US-08 Acknowledge<br>US-09 Reassign and assign roles<br>US-10 Update status and severity | Must |
| **E4: Tracking and Records** | Keep an accurate history and track targets | US-11 Timeline<br>US-12 SLA tracking<br>US-17 Audit log | Must |
| **E5: Resolution and Learning** | Close incidents properly and learn from them | US-13 Resolve incident<br>US-14 Postmortem<br>US-15 Search past incidents | Must |
| **E6: Insights** | Show trends to managers | US-16 Dashboard<br>US-19 DORA metrics | Must / Should |
| **E7: Extensions** | Optional features after the core works | US-18 AI help<br>US-20 Customer updates | Should / Could |

**Milestone:** finishing E1 to E3 gives a working end-to-end flow: alert comes in, incident is created, assigned, escalated, and acknowledged.

## 2. Survey Overview

- **Responses:** 24 (16 undergraduates in 3rd/4th year, 5 in 1st/2nd year, 2 working professionals, 1 postgraduate)
- **By role:** Resolver 5, Manager/Reviewer 4, Incident Lead 3, User 3, Scribe 3, Service Owner 3, Support 2, Tool Admin 1
- **Experience:** mostly college projects (13); 7 had no direct experience

**Limitation:** each role-specific question was answered by only 1 to 5 people, mostly students. Findings below are early signals, not final agreements. They should be confirmed in follow-up interviews (4 respondents shared contact details).

## 3. Conflicts and Proposed Resolutions

| # | Conflict | Survey evidence | Proposed resolution | Affects |
|---|---|---|---|---|
| C1 | **Individual vs team-level reports** | Reviewers: 3 of 4 want both team and individual metrics; 1 wants team-only. SRS says team-level by default. | Keep team-level reports as the default. Each person can see their own metrics, but there are no rankings or comparisons between individuals. | DR-07.2, US-16 |
| C2 | **AI suggestions: review vs apply directly** | Resolvers: 3 of 5 would review first; 2 would apply directly. | Keep human review mandatory, but make it one click (Accept / Reject). Speed is preserved without unsafe automation. | FR-19, US-18 |
| C3 | **Can one person lead and take notes?** | Incident Leads: 2 of 3 say only for minor problems; 1 says yes. | Allow it for SEV3 and SEV4. For SEV1 and SEV2, show a warning recommending a separate Scribe, but do not block it. | DR-02.2, US-09 |
| C4 | **Which incidents need a postmortem?** | Incident Leads: 2 of 3 say critical and high-impact only; 1 says all. | Postmortem required before closure for SEV1 and SEV2. Optional for SEV3 and SEV4. | DR-06.3, US-14 |
| C5 | **How often to update customers** | Users: 2 of 3 want updates only when something changes; 1 wants every 30 minutes. | Post an update on every status change. For SEV1, also post at least every 30 minutes even with no change. | FR-21, US-20 |
| C6 | **Do nights and weekends count toward SLAs?** | Service Owners: 2 of 3 say count all hours; 1 says it depends on severity. | Count all hours in the first version (simple and matches the majority). Severity-based business hours stays a Could. | FR-16.4, FR-16.5, US-12 |
| C7 | **Response time targets differ** | Critical: 2 of 3 say within 15 minutes, 1 says within 1 hour. Minor: 2 of 3 say within 1 day, 1 says within 3 days. | Targets are configurable per service. Default values: SEV1 respond within 15 minutes, SEV4 respond within 1 day. | FR-14.1, US-05 |
| C8 | **Customer updates rated important, but scoped as Could** | 18 of 24 rated public status updates somewhat or very important. FR-21 is Could. | Keep as Could to protect the core workflow. Revisit after E1 to E5 are complete. | FR-21, US-20 |
| C9 | **Reviewers want real-time dashboards, but live updates are Should** | All 4 reviewers chose a live (real-time) dashboard. | Dashboard shows fresh data on page load in the first version. Live push updates come in a later sprint. | FR-12.3, US-16 |

## 4. Findings That Support the Current Requirements

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

## 5. Gaps Still Open

- **Communications Lead:** no responses to the questions on approval, update frequency, and separating internal and public information. Needs an interview.
- **Platform Admin:** only 1 response. Access levels, role separation, and integration priority need more input.
- **Support:** only 2 responses, with no clear process for how user complaints reach the team.
- **Coordination and recording challenges:** the open-text questions on these had no answers.
