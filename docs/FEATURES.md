# Features

Everything Settle will do, how people use it, and when it is built. The AI features, the AI SRE,
and the MCP server are in the [AI/ML roadmap](plans/ai-ml-roadmap.md). The sprints are in
[sprint-plan.md](sprint-plan.md).

The product takes the best of two tools:
- **incident.io** for incident response: declaring, roles, the timeline, postmortems, and insights.
- **Better Stack** for monitoring: uptime checks, heartbeats, logs and metrics, and the status page.

**About the tables:**
- **Req.** is the requirement in `REQUIREMENTS.md`. *New* means it still has to be added there
  before it is built.
- **Sprint** is when it is planned.

## Access and administration

| Feature | How it is used | Req. | Sprint |
| --- | --- | --- | --- |
| Login with three roles | Admin, Engineer, and Viewer. Viewers read; Engineers respond; Admins configure | FR-01 | 1 |
| User administration | An Admin creates users, changes roles, and disables accounts. A role change logs the user out everywhere | FR-01 | 1 (disabling an account has no screen or endpoint yet) |
| Audit log | Every configuration change, with before → after values. Read-only | FR-13 | 1–4 |
| Personal access tokens | A user creates a token to connect an AI tool through MCP. Hashed and revocable | *New* | 4 |

## Services and ownership

| Feature | How it is used | Req. | Sprint |
| --- | --- | --- | --- |
| Service catalog | Each service has an owner, criticality, fallback contact, and response and resolution targets per severity | FR-14, FR-16 | 1 |
| Service health | Each service shows Operational, Degraded, or Down, from its monitors | FR-22 | 2 |
| Service map | A graph of services, databases, and external providers. Each node shows live health; each link shows traffic, errors, and latency. **Blast radius:** what breaks if this service fails | *New* | 4 (links entered by hand), 5 (links from traces) |
| Runbooks | Step lists per service. The incident page offers the matching runbook | *New* | 4 |
| Deploy tracking | CI reports each deploy (service, version, commit, author). Deploys appear on the timeline of incidents in the same time window | FR-20 | 4 |

## On-call and alerts

| Feature | How it is used | Req. | Sprint |
| --- | --- | --- | --- |
| On-call schedules | Shifts per service, "on call now", and the fallback contact | FR-15 | 2 |
| Escalation policies | Levels and wait times. An unacknowledged incident notifies the next level; it never reassigns | FR-07 | 3 |
| Alert intake | Monitoring tools, Grafana rules, and our own monitors post to one webhook, each with its own token | FR-03 | 2 |
| Deduplication and grouping | A repeated event counts once. Related alerts join an open incident by an explicit correlation key, within one service | FR-03 | 2 |
| Alert inbox | New alerts (each opened an incident) and attached ones (each joined an incident), with how many alerts share the incident and a "routed to me" view | FR-03 | 2–3 |
| Notifications | An in-app bell for assignments, escalations, and mentions | FR-07 | 2 |

## Incident response

| Feature | How it is used | Req. | Sprint |
| --- | --- | --- | --- |
| Declare an incident | By hand, or automatically from an alert. Assigned to whoever is on call | FR-02, FR-05 | 1–2 |
| Severity SEV1–SEV4 | Changed with a reason. SLA targets stay as they were at creation | FR-04 | 3 |
| Status lifecycle | Open → Acknowledged → Investigating → Identified → Monitoring → Resolved → Closed, plus Investigating ↔ Monitoring | FR-06 | 3 |
| Incident roles | Lead, Comms, and Ops. See [Incident roles](#incident-roles) | FR-05 | 3 |
| SLA clocks | Response and resolution clocks, each running, at risk, breached, or met | FR-16 | 1–3 |
| Timeline | Append-only: alerts, status changes, comments, corrections, deploys, and AI findings | FR-08, FR-09 | 1–3 |
| Evidence links | Links to logs, charts, and deploys attached to the incident | FR-09 | 4 |
| Customer updates | Comms posts status page updates from inside the incident | FR-21 | 4 |
| Closure gate | SEV1 and SEV2 incidents need a reviewed postmortem before they can close | DR-06 | 5 |

## After the incident

| Feature | How it is used | Req. | Sprint |
| --- | --- | --- | --- |
| Postmortem editor | Summary, impact, timeline, cause, and what went well or badly | FR-11 | 5 |
| Follow-ups | Action items with an owner, due date, and status; overdue ones are flagged | FR-11 | 5 |
| Insights | MTTA and MTTR (mean, median, P90), incidents by severity and service, SLA breach rate, follow-up completion, and out-of-hours pager load, over 7, 30, or 90 days | FR-12 | 5 |
| History search | Search past incidents and how they were fixed | FR-17 | 4 |

## Monitoring

| Feature | How it is used | Req. | Sprint |
| --- | --- | --- | --- |
| Uptime monitors | HTTP status, a keyword on a page, ping or TCP, and SSL expiry. Each monitor has an interval and a linked service | FR-22 | 2 |
| Checks from several regions | Probes check from 2–3 regions. A monitor is **down only when at least 2 regions agree**, so one bad network path doesn't wake anyone | *New* | 3 |
| Monitor detail | Response-time chart, results per region, check log, and uptime % | FR-22 | 2–3 |
| Heartbeats | A scheduled job pings its URL; a late or missing ping raises an alert | *New* | 2 |
| Public status page | Components mapped to services, 90-day uptime, the active incident with its updates, and history | FR-21 | 4 |

## Logs and metrics

| Feature | How it is used | Req. | Sprint |
| --- | --- | --- | --- |
| Ingestion | Apps send logs and metrics with OpenTelemetry to Grafana Cloud (Loki for logs, Prometheus for metrics) | *New* | 2 |
| Logs explorer | Search by service, level, and text; live tail; click a JSON field to filter; similar lines grouped into patterns; saved views | *New* | 4 |
| Metrics dashboards | Per service: request rate, error rate, and latency (p50 and p95) | *New* | 4 |
| Logs and charts on the incident | The incident page shows the logs and charts for **that service and that time window** | FR-09 | 4 |
| Log and metric alerts | Grafana alert rules post into our alert intake | FR-03 | 3 |

The backend runs every telemetry query, so the Grafana token never reaches the browser or the AI.

## Incident roles

Proposed; decision D39 is still open.

| Role | Who | Responsible for | Can |
| --- | --- | --- | --- |
| **Lead** | The assignee. The first person to acknowledge becomes Lead | Decisions and the status of the incident | Change status and severity, assign roles, and resolve |
| **Comms** | Chosen by the Lead | Status page and stakeholder updates | Post customer updates |
| **Ops** | Chosen by the Lead | Hands-on investigation and fixing | Comment and add evidence |

- **Lead replaces FR-05.4's "Commander".**
- **Scribe is dropped:** the timeline and the AI drafting feature do that job.
- **One person may hold several roles.** On SEV1 and SEV2 incidents, Settle shows a warning when
  that happens (FR-05.5).
- **Every role change goes on the timeline,** and never resets the SLA clocks.

## Demo shop

A small payments, checkout, and search app with a traffic generator. Its fault switches are a slow
gateway, an error spike, a bad deploy (which sends a real deploy event), a queue backlog, and an
expiring certificate.

Each switch is a scenario with a known cause. The demo shop is how we demonstrate and test
monitors, alerts, telemetry, and the AI SRE. Sprint 2.

## One complete flow

1. **Detected:** someone switches on "bad deploy" in the demo shop. Monitors find checkout down
   from 2 regions, and Grafana sees the error rate jump.
2. **Declared:** the alerts become one SEV2 incident, and the on-call engineer is paged.
3. **Acknowledged:** they acknowledge, which meets the response SLA and makes them Lead.
4. **Investigated:** the AI SRE has already posted its top hypothesis, with evidence: the deploy
   time, the new log pattern, the drop in success rate, and the external provider showing as
   healthy.
5. **Fixed:** the Lead accepts the suggested rollback, which is recorded, and does the rollback
   themselves. Comms updates the status page. Status moves to Identified, then Monitoring.
6. **Resolved:** monitors recover, and the Lead resolves with a recovery note.
7. **Learned from:** the AI drafts the postmortem, the team edits it and adds follow-ups, and
   Insights updates MTTA and MTTR.
