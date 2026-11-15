# Severity Guide

Operational guide for incident severity classification (AI-4, FR-04.1, US-01).

Each incident declared in the system is assigned a severity from **SEV1** (highest) to **SEV4** (lowest). The severity determines default response and resolution SLA targets, escalation urgency, and responder roles.

---

## Severity Levels

### SEV1 — Critical Outage
- **Definition:** Catastrophic failure or complete unavailability of core business functions with widespread user impact and no workaround.
- **Examples:**
  - Total checkout or payment authorization outage (`/payments/authorize` failing 100%).
  - Customer data corruption or unrecoverable data loss.
  - Active security compromise or widespread unauthorized access.
  - Core database cluster failure taking down all customer-facing endpoints.
- **Response Target:** 15 minutes.
- **Resolution Target:** 240 minutes (4 hours).
- **Incident Roles:** Dedicated Incident Lead and separate Scribe strongly recommended (C3).

### SEV2 — Major Degradation
- **Definition:** Significant service impairment affecting a large proportion of users or critical workflows, but with partial operation or secondary systems impacted.
- **Examples:**
  - Elevated payment latency (>2 seconds) causing frequent client timeouts or cart abandonment.
  - High error rate on checkout transactions (e.g. >50% failure rate).
  - Primary payment gateway failing over to backup gateway with degraded throughput.
  - Core service deployment regression requiring rapid rollback or hotfix.
- **Response Target:** 60 minutes (1 hour).
- **Resolution Target:** 480 minutes (8 hours).
- **Incident Roles:** Dedicated Incident Lead recommended.

### SEV3 — Moderate Degradation
- **Definition:** Isolated service degradation or non-critical background failure with an available workaround or limited user blast radius.
- **Examples:**
  - Background order fulfillment queue backlog or processing worker paused.
  - Delayed email, SMS, or Slack notification delivery.
  - Administrative reporting, metrics export, or CSV download failures.
  - Intermittent errors on non-critical user settings pages.
- **Response Target:** 240 minutes (4 hours).
- **Resolution Target:** 1440 minutes (24 hours).
- **Incident Roles:** Lead may also take scribe notes.

### SEV4 — Minor Issue
- **Definition:** Low-impact bug, cosmetic flaw, documentation error, or routine operational observation with zero impact on transactional flows.
- **Examples:**
  - Minor UI styling, formatting, or wordmark alignment glitch.
  - Upcoming TLS certificate renewal notice (>14 days until expiry).
  - Minor non-blocking log warning without user-visible errors.
  - Scheduled maintenance or routine configuration cleanup task.
- **Response Target:** 1440 minutes (24 hours).
- **Resolution Target:** 4320 minutes (72 hours).
- **Incident Roles:** Single responder.

---

## Summary Matrix

| Severity | Customer Impact | System Scope | Default Response Target | Default Resolution Target |
|---|---|---|---|---|
| **SEV1** | Critical / Catastrophic | Core platform / All regions | 15 min | 4 hours |
| **SEV2** | High / Degraded | Core workflow / Subset of users | 60 min | 8 hours |
| **SEV3** | Medium / Isolated | Background tasks / Non-critical service | 4 hours | 24 hours |
| **SEV4** | Low / Cosmetic | Non-blocking / Cosmetic | 24 hours | 72 hours |
