# Demo Shop Scenarios

**Issue:** #64 / #76  
**Decision:** D40 (What the demo shop simulates)  
**Status:** Approved / Recorded for Sprint 2  

This document defines the authoritative five failure scenarios simulated by the Demo Shop (`demo-shop`). Each scenario corresponds to an independently controllable fault switch. These scenarios generate real, measurable signals used to test monitors, alerts, OpenTelemetry ingestion into Grafana Cloud, and the AI SRE evaluation loop (Sprint 3).

---

## 1. Scenario Summary Table

| ID | Switch Name | Service | What it Breaks | Signals Expected | Real Cause | AI Hypothesis to Rank First |
|---|---|---|---|---|---|---|
| **SCN-01** | `slow_gateway` | `payments` | Injects 2.0s bounded latency on payment authorization (`/payments/authorize`), causing checkout latency spikes and client timeouts. | **Monitor:** Latency monitor triggers (>1.5s).<br>**Metrics:** `demo_http_request_duration_seconds` spikes.<br>**Logs:** `WARN payment.gateway.slow latency_ms=...`. | Third-party payment gateway network degradation causing severe latency. | Downstream payment gateway latency causing connection backpressure and checkout timeouts. |
| **SCN-02** | `error_spike` | `checkout` | Injects simulated internal errors into checkout transactions, causing ~80% of checkout calls to return HTTP 500. | **Monitor:** Consecutive 500 errors.<br>**Metrics:** `demo_http_requests_total{status="500"}` spikes.<br>**Logs:** `ERROR checkout.transaction_failed`. | Unhandled database deadlock in checkout transaction block. | Unhandled internal exception in checkout transaction block causing error rate surge. |
| **SCN-03** | `bad_deploy` | `checkout` | Simulates a broken deployment: changes version from `v1.0.0` to `v1.0.1-broken`, causes immediate checkout failures, and emits a structured deploy event. | **Monitor:** Sudden error rate jump immediately post-deploy.<br>**Metrics:** `demo_deploy_events_total{version="v1.0.1-broken"}` increments.<br>**Logs:** `deploy.event` followed by `ERROR checkout.null_pointer_exception`. | Faulty code release `v1.0.1-broken` deployed to checkout service. | Regression defect introduced in release `v1.0.1-broken` deployed at deploy timestamp. |
| **SCN-04** | `queue_backlog` | `order-fulfillment` | Pauses asynchronous order fulfillment queue processing while new orders arrive, causing queue depth and processing lag to grow. | **Monitor:** Queue depth/lag monitor triggers.<br>**Metrics:** `demo_queue_depth` increases continuously; `demo_queue_lag_seconds` rises.<br>**Logs:** `WARN queue.backlog_warning depth=...`. | Asynchronous queue consumer thread paused, halting message processing. | Asynchronous queue worker stall causing order processing queue depth accumulation. |
| **SCN-05** | `expiring_certificate` | `payments` | Simulates an expiring/expired TLS certificate on the external payment gateway, dropping remaining validity to 0 days and emitting SSL handshake errors. | **Monitor:** SSL certificate monitor triggers on expiration (0 days remaining).<br>**Metrics:** `demo_certificate_days_until_expiry` drops to 0; `demo_ssl_errors_total` increments.<br>**Logs:** `WARN/ERROR ssl.cert_expiring host='gateway.payments.internal'`. | TLS certificate on `gateway.payments.internal` expired without automatic renewal. | TLS/SSL certificate expiration on payment gateway endpoint causing handshake failures. |

---

## 2. Fault Control Mechanics

Each fault switch can be enabled, disabled, or inspected via the Demo Shop control interface:

- **List switches:** `GET /faults`
- **Toggle switch:** `POST /faults/{fault_name}` with payload `{"enabled": true}` or `{"enabled": false}`
- **Reset all switches:** `POST /faults/reset` (restores healthy state for all services)

### Recovery Verification
When any switch is turned off, the service recovers deterministically:
1. `slow_gateway` disabled -> authorization latency drops back to <10ms.
2. `error_spike` disabled -> checkout success rate returns to 100%.
3. `bad_deploy` disabled -> service rolls back to `v1.0.0`, emitting a rollback deploy event, and checkout succeeds normally.
4. `queue_backlog` disabled -> background worker resumes, drains the backlog, and queue depth returns to 0.
5. `expiring_certificate` disabled -> certificate validity restores to normal (90 days).

---

## 3. Telemetry Conventions

- **Metrics:** Emitted using OpenTelemetry SDK with namespace `demo_*` (e.g., `demo_http_requests_total`, `demo_http_request_duration_seconds`, `demo_queue_depth`, `demo_deploy_events_total`, `demo_certificate_days_until_expiry`, `demo_fault_active`).
- **Logs:** JSON structured logs containing `timestamp` (UTC ISO-8601), `service`, `severity`, `event`, and contextual metadata (`request_id`, `fault_id`, `version`).
- **Delivery:** Exported via OTLP to Grafana Cloud when configured, or retained in local mode during development and automated tests.
