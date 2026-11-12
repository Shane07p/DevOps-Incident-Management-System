# Demo Shop (`demo-shop`) — Sprint 2 (Issue #76)

**Project:** IT314 DevOps Incident Management System  
**Component:** Demo Shop v1  
**Scope:** Retail application simulation with payments, checkout, search, traffic generator, OpenTelemetry, and 5 independent fault switches.

---

## 1. Architecture & Services

The Demo Shop simulates a modern micro-retail system emitting real telemetry to OpenTelemetry / Grafana Cloud:

1. **Search Service (`/products`, `/search`)**: Catalog of products, fast deterministic text search.
2. **Payments Service (`/payments/authorize`)**: Simulates third-party credit card gateway authorization.
3. **Checkout Service (`/checkout`)**: Coordinates cart validation, payment execution, and asynchronous order queueing.
4. **Order Fulfillment Queue**: Real in-process bounded queue with background worker.
5. **Fault Switch Manager (`/faults`)**: 5 independent failure switches with immediate observable signals and deterministic recovery.
6. **Traffic Generator (`/traffic`)**: Bounded, configurable background load generator.
7. **OpenTelemetry Telemetry**: Real OpenTelemetry metrics (`demo_*`) and JSON structured logs.

---

## 2. Five Fault Scenarios (#64, D40)

| Scenario ID | Switch Name | Service | Observable Failure | Recovery When Disabled |
|---|---|---|---|---|
| **SCN-01** | `slow_gateway` | `payments` | Adds 2.0s bounded latency on `/payments/authorize`, spiking `demo_http_request_duration_seconds`. | Latency returns immediately to <10ms. |
| **SCN-02** | `error_spike` | `checkout` | Causes ~80% of `/checkout` calls to fail with HTTP 500 (database deadlock simulation). | Success rate returns immediately to 100%. |
| **SCN-03** | `bad_deploy` | `checkout` | Deploys `v1.0.1-broken`, emits a structured deploy event, and fails checkouts with NullPointerException. | Rolls back to `v1.0.0`, emits rollback event, checkout recovers. |
| **SCN-04** | `queue_backlog` | `order-fulfillment` | Pauses order fulfillment worker; `demo_queue_depth` and processing lag grow. | Worker unpauses, drains the backlog, queue depth returns to 0. |
| **SCN-05** | `expiring_certificate` | `payments` | Drops payment gateway certificate validity to 0 days; emits SSL handshake warnings/errors. | Certificate validity restored to 90 days. |

---

## 3. Getting Started Locally

### Prerequisites
- Python 3.12+ (or 3.14+)
- Dependencies installed:
```powershell
cd demo-shop
pip install -r requirements-dev.txt
```

### Run the Server
```powershell
uvicorn demo_shop.app:app --host 0.0.0.0 --port 8001 --reload
```
Interactive Swagger docs: `http://localhost:8001/docs`

### Control token for a public demo

Set `DEMO_SHOP_CONTROL_TOKEN` to a private value in the server's environment before exposing the
shop through a tunnel. Send `Authorization: Bearer <token>` on every `/faults` and `/traffic`
control request, including reads. Missing or incorrect credentials return HTTP 401. Keep the token
out of source control and logs. The payment, checkout, search, products, health, and readiness
routes stay open so probes and generated traffic can use them.

With `ENVIRONMENT=production`, the server refuses to start without a control token. An empty
token is allowed in other environments for local development. Export the same token in the
shell running the `curl` examples below as `DEMO_SHOP_CONTROL_TOKEN`.

---

## 4. Running via Docker Compose

The Demo Shop runs under the optional `demo` profile so it does not interfere with the default database workflow:

```bash
# Start Demo Shop
docker compose --profile demo up -d demo-shop

# View logs
docker compose logs -f demo-shop
```

---

## 5. Traffic Generator

### Via REST API
```bash
# Start 30 seconds of traffic at 3 requests/sec
curl -X POST http://localhost:8001/traffic/start \
  -H "Authorization: Bearer $DEMO_SHOP_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"rate": 3.0, "duration_seconds": 30, "target_url": "http://localhost:8001"}'

# Check status
curl http://localhost:8001/traffic/status -H "Authorization: Bearer $DEMO_SHOP_CONTROL_TOKEN"

# Stop traffic early
curl -X POST http://localhost:8001/traffic/stop -H "Authorization: Bearer $DEMO_SHOP_CONTROL_TOKEN"
```

### Via CLI
```powershell
python -m demo_shop.traffic --rate 2.0 --duration 30 --url http://localhost:8001
```

---

## 6. Fault Control

```bash
# Inspect all switches
curl http://localhost:8001/faults -H "Authorization: Bearer $DEMO_SHOP_CONTROL_TOKEN"

# Enable Slow Gateway
curl -X POST http://localhost:8001/faults/slow_gateway \
  -H "Authorization: Bearer $DEMO_SHOP_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"enabled": true}'

# Enable Bad Deploy (emits deploy event)
curl -X POST http://localhost:8001/faults/bad_deploy \
  -H "Authorization: Bearer $DEMO_SHOP_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"enabled": true}'

# Retrieve last deploy event
curl http://localhost:8001/faults/deploy-event -H "Authorization: Bearer $DEMO_SHOP_CONTROL_TOKEN"

# Reset all faults to healthy baseline
curl -X POST http://localhost:8001/faults/reset -H "Authorization: Bearer $DEMO_SHOP_CONTROL_TOKEN"
```

---

## 7. OpenTelemetry & Grafana Cloud (D34, #75)

### Local / Test Mode (Default)
When no remote endpoints are configured in environment variables, the Demo Shop runs in local mode using `InMemoryMetricReader` and standard stdout JSON logs. No external network requests are made and local unit tests pass without credentials.

### Connecting to Grafana Cloud
Set the following environment variables in `.env` or your shell:

```bash
# OTLP Endpoint from Grafana Cloud Portal
OTEL_EXPORTER_OTLP_ENDPOINT="https://otlp-gateway-prod-us-central-0.grafana.net/otlp"

# Basic auth: Base64-encoded "<instance_id>:<token>"
OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic <BASE64_TOKEN>"
```

Metrics are delivered to Prometheus via OTLP; structured JSON logs can be collected by Promtail/Alloy or sent via OTLP.

---

## 8. Automated Tests

```powershell
cd demo-shop
pytest -v
```

All 5 fault switches, traffic generator, telemetry instruments, and scenario definitions are thoroughly validated without external network dependencies.
