# Grafana checks and alerts (D35, D36)

Grafana Cloud checks the demo shop from 2 locations, and its alert rules post into Settle's
`/webhooks/alerts` (#88). These files record that setup so it can be reviewed and created again;
[Setting it up](#setting-it-up) lists the steps as they were run on 13 Nov 2026.

```
grafana/
├── checks/synthetic-checks.yaml               # Synthetic Monitoring HTTP checks
├── alerts/alert-rules.yaml                    # Down, Degraded, Error rate rules
├── contact-points/webhook-contact-point.yaml  # payload template, 3 contact points
└── scripts/create_rules.py                    # creates the rules in a stack from alert-rules.yaml
```

## Where it runs

| Part | Where |
| --- | --- |
| Settle backend | Render, the main workspace: `https://incident-backend-f340.onrender.com` |
| Demo shop | Render, a workspace of its own: `https://settle-demo-shop.onrender.com` |
| Checks, the shop's metrics, alert rules, contact points | Grafana Cloud stack, free tier |

## Checks (D35)

| Check | Request | Every | Locations |
| --- | --- | --- | --- |
| `demo-shop-payments` | `POST /payments/authorize` | 5 min | Singapore, Mumbai |
| `demo-shop-checkout` | `POST /checkout` | 2 min | Singapore, Mumbai |
| `demo-shop-search` | `GET /search?q=widget` | 5 min | Singapore, Mumbai |

One execution is one check from one location: 17,280 + 43,200 + 17,280, about 78k a month, inside
the free tier's 100k API test executions a month. The request bodies match the demo shop's models;
any other body gets 422 and the check fails every time. The probes are in Asia, where the shop
and its users are; any two locations work, since the rules count locations, not names.

## Alert rules (D36)

| Rule | Fires when | Severity |
| --- | --- | --- |
| Down | at least 2 locations fail, on 2 checks in a row | critical, SEV1 |
| Degraded | at least 2 locations take longer than 2 s, on 3 checks in a row | warning, SEV2 |
| Error rate | more than 5% of the endpoint's requests return 5xx, for 5 minutes | warning, SEV2 |

Down and Degraded count locations in the query (`sum by (job)` over each location's newest
result), and `for:` sets the checks in a row from the check's frequency: Down waits 6 min on a
5-minute check and 3 min on a 2-minute one, Degraded 11 and 5. Change a check's frequency and
change those with it. The header of `alert-rules.yaml` explains the arithmetic.

Error rate reads the shop's own metric, `demo_http_requests_total`, by its `service` and `status`
labels. The shop pushes it over OTLP once a minute (`demo-shop/README.md`, section 7).

## Into Settle

Each rule type has its own contact point and its own tool token, and each rule names its contact
point itself (Grafana's simplified routing), so no notification policy is needed. The
`settle.alert` template turns the firing alert into Settle's alert format:

```json
{
  "source": "grafana-down",
  "eventId": "gf-<fingerprint>-<startsAt in Unix seconds>",
  "service": "payments",
  "title": "Payments is down",
  "description": "At least 2 locations failed the payments check on 2 checks in a row.",
  "severity": "SEV1",
  "correlationKey": "grafana-down-payments",
  "details": { "rule": "Payments is down", "ruleType": "down", "fingerprint": "<fingerprint>" }
}
```

- While one outage lasts, every repeat has the same `eventId` and body, so Settle counts it once.
  The next outage starts at a new time, so it gets a new `eventId` and can open a new incident.
- Alerts with the same `correlationKey` join the incident that is still open.
- Resolved notifications are not sent; Settle resolves incidents through its own status flow.

## Setting it up

No secret goes in the repository or a chat: each token below is pasted only into the setting that
uses it. Paste values without the surrounding quotes some pages show.

### 1. The demo shop on Render

Use a Render workspace of its own. A workspace gets 750 free instance hours a month; the backend
stays awake (the uptime bot) and so does the shop (the checks), about 744 hours each, so together
they would run out mid-month and Render would suspend both.

New Web Service from this repository: branch `main`, root directory `demo-shop`, runtime Docker,
Dockerfile `demo-shop/Dockerfile`, Free plan, health check path `/health`. With the root
directory set, only changes under `demo-shop/` redeploy it.

| Variable | Value |
| --- | --- |
| `PORT` | `8001` |
| `ENVIRONMENT` | `production`: the shop then refuses to start without a control token |
| `DEMO_SHOP_CONTROL_TOKEN` | `openssl rand -hex 32`; the fault, traffic, and log routes need it |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Cloud Portal > the stack > **OpenTelemetry** > **Configure**: the `https://otlp-gateway-….grafana.net/otlp` value |
| `OTEL_EXPORTER_OTLP_HEADERS` | the same page, after **Generate now** for a token: `Authorization=Basic …` |

Check it: `GET /search?q=widget` answers 200, `GET /faults` without the token answers 401, and in
Grafana **Explore** on the `grafanacloud-…-prom` data source, `demo_http_requests_total` shows
series with a `service` label within a couple of minutes.

### 2. The three checks

**Testing & synthetics > Synthetics > Checks > Add new check > API Endpoint**, once per check in
`checks/synthetic-checks.yaml`:

1. **Request:** the job name exactly as in the file (the rules find each check by it), the method,
   and the URL on `https://settle-demo-shop.onrender.com`. For the two POST checks, **Request
   options > Options**: header `Content-Type: application/json`; **Body**: the body from the file.
2. **Uptime:** valid status code `200`; timeout 10 s.
3. **Execution:** probes Singapore and Mumbai; the frequency from the table above.
4. **Alerting:** untick **Failed Checks** and leave the email box empty. Our rules do the alerting,
   and an email address there creates a contact point and a notification policy.
5. **Test** answers 200; save.

### 3. Settle

As an Admin of the org, in production:

- **Services** named `payments`, `checkout`, and `search` (case does not matter); an alert for a
  service Settle does not know is refused. Leave **Telemetry label** empty: it defaults to the
  name, which is the shop's `service` label. SLA targets used, in minutes (response / resolution):
  SEV1 15 / 240, SEV2 60 / 480, SEV3 240 / 1440, SEV4 1440 / 4320.
- **Tools** (`/tools`; Ctrl+K and "Tools" also finds it): "Grafana Down", "Grafana Degraded",
  "Grafana Error rate". Each shows its token once.

### 4. Template and contact points

**Alerts & IRM > Alerting > Notification configuration**:

1. **Templates:** add `settle-alert` with the `template` text from
   `contact-points/webhook-contact-point.yaml`.
2. **Contact points:** add `settle-down`, `settle-degraded`, and `settle-error-rate`, each with one
   **Webhook** integration:
   - URL `https://incident-backend-f340.onrender.com/webhooks/alerts`, method POST;
   - **Optional Webhook settings:** Authorization Header, scheme `Bearer`, credentials the matching
     tool token; **Custom Payload** on, template `{{ template "settle.alert" . }}`;
   - **Notification settings:** tick **Disable resolved message**. The list then shows
     "Delivering only firing notifications".

Leave **Notification policies** alone: the stack's tree also routes Grafana's own rules.

### 5. The rules

`scripts/create_rules.py` creates the folder "Demo Shop Alerts" and the three rule groups from
`alerts/alert-rules.yaml`, evaluated every minute, each rule sending to its contact point. Running
it again replaces the same groups, and the rules stay editable in the UI.

1. **Administration > Users and access > Service accounts:** add one with the Admin role (the
   rule API needs it) and generate a token.
2. In your own terminal (PowerShell shown; it needs PyYAML, `python -m pip install pyyaml`):
   ```powershell
   $env:GRAFANA_URL = "https://<stack>.grafana.net"
   $env:GRAFANA_TOKEN = "<service account token>"
   python grafana/scripts/create_rules.py
   ```
   It ends with `9 of 9 rules in place.`
3. Delete the service account.

**Alerting > Alert rules** then shows 9 rules in Demo Shop Alerts, and the contact points are no
longer marked Unused.

### 6. Test

Run TC-D36a to f in `docs/4-testing/test-cases.md` with the demo shop's fault switches:

| Fault | What the checks see | Cases |
| --- | --- | --- |
| `bad_deploy` | every checkout answers 500 | b (Pending after one failing check), c (Checkout is down, SEV1), f (no second incident while it lasts); Checkout error rate fires too |
| `slow_gateway` | payments takes 2 s longer, and so does checkout, which calls the payment service | d (Pending after 2 slow checks), e (Payments is slow, SEV2, about 14 minutes in); Checkout is slow fires first, as its check runs every 2 minutes |

A fault switch fails every location at once, so TC-D36a (1 of 2 locations failing) cannot be run
with one. The results of 13 November are in `docs/4-testing/test-report.md` (Sprint 3).

Switching a fault on and off:

```bash
curl -X POST https://settle-demo-shop.onrender.com/faults/error_spike \
  -H "Authorization: Bearer $DEMO_SHOP_CONTROL_TOKEN" \
  -H "Content-Type: application/json" -d '{"enabled": true}'
curl -X POST https://settle-demo-shop.onrender.com/faults/reset \
  -H "Authorization: Bearer $DEMO_SHOP_CONTROL_TOKEN"
```

The files use Grafana's file-provisioning format, which is also what Alerting's export produces.
`create_rules.py` finds the stack's Prometheus data source itself; the files say
`grafanacloud-prom`, the usual UID.
