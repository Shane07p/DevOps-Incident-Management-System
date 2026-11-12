# Grafana Monitoring Configuration

Exported Grafana Cloud checks, alert rules, and contact points for the
DevOps Incident Management System demo-shop.

## Directory structure

```
grafana/
├── checks/
│   └── synthetic-checks.yaml      # HTTP probes for payments, checkout, search
├── alerts/
│   └── alert-rules.yaml           # Down, Degraded, and Error Rate rules
├── contact-points/
│   └── webhook-contact-point.yaml # Webhook → /webhooks/alerts with payload template
└── README.md
```

## Checks (D35)

| Endpoint          | Frequency | Locations           |
|-------------------|-----------|---------------------|
| `/payments/authorize` | 5 min | Frankfurt, New York |
| `/checkout`           | 2 min | Frankfurt, New York |
| `/search?q=widget`    | 5 min | Frankfurt, New York |

Free-tier budget: 3 checks × 2 locations × ~8 640 intervals/month ≈ 52 k executions/month
(well within Grafana Cloud's 10 k free checks; at 5 min interval we use far fewer).

## Alert rules (D36)

| Rule          | Condition                                              | Severity |
|---------------|--------------------------------------------------------|----------|
| **Down**      | ≥ 2 locations fail on 2 consecutive checks             | Critical (SEV1) |
| **Degraded**  | ≥ 2 locations respond > 2 s on 3 consecutive checks    | Warning  (SEV2) |
| **Error rate**| 5xx / total > 5 % for 5 minutes                        | Warning  (SEV2) |

## Contact point

A custom payload template transforms Grafana's native alert JSON into our
`AlertRequest` format:

```json
{
  "source": "grafana-down",
  "eventId": "gf-abc123def456",
  "service": "Payments",
  "title": "Payments endpoint is down",
  "description": "Synthetic monitoring probes ...",
  "severity": "SEV1",
  "correlationKey": "grafana-down-payments",
  "details": { ... }
}
```

Repeated alerts with the same `correlationKey` join the same incident.

## Setup

1. Register a monitoring tool in the backend:
   ```
   POST /api/tools  { "name": "Grafana Alerting" }
   ```
   Save the returned `token`.

2. Set environment variables in Grafana Cloud:
   - `INCIDENT_BACKEND_URL` — e.g. `https://your-app.example.com`
   - `GRAFANA_TOOL_TOKEN` — the tool token from step 1

3. Import the YAML files through the Grafana provisioning API or UI.

4. Verify by triggering a demo-shop fault switch and watching the alert
   arrive at `/webhooks/alerts`.
