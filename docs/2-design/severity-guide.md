# Severity Guide

Severity levels define the impact of an incident and determine the response and resolution targets.

## SEV1 — Critical

Total or near-total service outage affecting all or most users. Business-critical operations are
stopped. Requires immediate response from all available responders.

**Examples:** payment service fully down, authentication unreachable for all users, data loss in
progress.

## SEV2 — High

Significant degradation of a core service. A large portion of users are affected or a critical
business function is unavailable. Requires urgent attention.

**Examples:** checkout timeouts affecting 30 % of users, API error rate above 10 %, payments slow
but not fully down.

## SEV3 — Moderate

Partial or intermittent degradation. A minority of users are affected, or a non-critical feature is
unavailable. Requires a prompt fix within business hours.

**Examples:** occasional 5xx errors on a secondary endpoint, dashboard loading slowly, one region
degraded while others are healthy.

## SEV4 — Low

Cosmetic or minor issue with negligible user impact. No data loss or availability risk.

**Examples:** typo in an error message, non-critical widget failing to render, a background job
running slightly slower than usual.

## Keyword Reference (used by the AI triage service)

| Severity | Trigger keywords |
|----------|-----------------|
| SEV1 | outage, down, unavailable, unreachable, data loss, all users, fully down, critical failure, payment fully, 100% error |
| SEV2 | timeout, high error rate, degraded, elevated latency, partial outage, majority, most users, slow, 50%, gateway |
| SEV3 | intermittent, flapping, some users, minority, non-critical, occasional, one region, partial |
| SEV4 | cosmetic, typo, ui issue, minor, low priority, background job, slow render |
