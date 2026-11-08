# Demo-Shop Scenario Table

Designed test scenarios for the five demo-shop fault switches. Each row describes what the
switch breaks, the signals the monitors and AI SRE should see, the real cause, and the
hypothesis the AI SRE should rank first.

The machine-readable copy is [`ai-service/data/scenarios.yaml`](../../ai-service/data/scenarios.yaml).

| Scenario | Switch | Service | What it breaks | Monitor | Metric | Log patterns | Deploy event | Real cause | AI first hypothesis |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| SCN-01 | slow_gateway | payments | Adds ≈ 2 s latency to /payments/authorize | HTTP response-time monitor for /payments/authorize reports elevated latency | `demo_http_request_duration_seconds` increases for payments authorization | `payment.gateway.slow` | — | Artificially injected 2-second delay in the payment gateway | Payment gateway latency degradation |
| SCN-02 | error_spike | checkout | ≈ 80 % of checkout requests fail with HTTP 500 | Checkout HTTP monitor observes increased HTTP 500 responses | `demo_http_requests_total` reflects increased failed checkout requests | `checkout.transaction_failed` | — | Simulated database deadlocks during checkout transactions | Database transaction deadlocks are causing checkout failures |
| SCN-03 | bad_deploy | checkout | Deploys v1.0.1-broken; checkout fails with NullPointerException | Checkout HTTP monitor observes HTTP 500 after deployment | `demo_http_requests_total` reflects failed checkout requests | `checkout.null_pointer_exception`, `deploy.event` | v1.0.1-broken deployed for checkout | A faulty deployment of checkout version v1.0.1-broken | A recent checkout deployment introduced a regression |
| SCN-04 | queue_backlog | order-fulfillment | Pauses the order fulfillment worker; queued orders accumulate | Queue depth and processing lag monitoring indicate a growing backlog | `demo_queue_depth`, `demo_queue_lag_seconds` increase | `queue.paused`, `queue.backlog_warning` | — | The background order fulfillment worker is paused | The order fulfillment worker is stalled or paused |
| SCN-05 | expiring_certificate | payments | Sets certificate validity to zero days; SSL warnings/errors | SSL certificate monitoring reports an expired or zero-day certificate | `demo_ssl_errors_total` increases, `demo_certificate_days_until_expiry` reaches zero | `ssl.cert_expiring`, `ssl.handshake_warning` | — | The simulated payment gateway TLS certificate has expired | Payment gateway TLS certificate expiry |
