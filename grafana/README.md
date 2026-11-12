# Grafana checks and alerts (D35, D36)

Grafana Cloud checks the demo shop from 2 locations, and its alert rules post into Settle's
`/webhooks/alerts` (#88). These files record that setup so it can be reviewed and created again.

```
grafana/
├── checks/synthetic-checks.yaml            # Synthetic Monitoring HTTP checks
├── alerts/alert-rules.yaml                 # Down, Degraded, Error rate rules
└── contact-points/webhook-contact-point.yaml  # template, 3 contact points, policy
```

## Checks (D35)

| Check | Request | Every | Locations |
| --- | --- | --- | --- |
| `demo-shop-payments` | `POST /payments/authorize` | 5 min | Frankfurt, New York |
| `demo-shop-checkout` | `POST /checkout` | 2 min | Frankfurt, New York |
| `demo-shop-search` | `GET /search?q=widget` | 5 min | Frankfurt, New York |

One execution is one check from one location: 17,280 + 43,200 + 17,280, about 78k a month, inside
the free tier's 100k API test executions a month. The request bodies match the demo shop's models;
any other body gets 422 and the check fails every time.

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

## Into Settle

Each rule type has its own contact point and its own tool token. The `settle.alert` template turns
the firing alert into Settle's alert format:

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

## Setting it up in Grafana Cloud

1. In Settle, as an Admin, register three tools (`POST /api/tools`): "Grafana Down", "Grafana
   Degraded", "Grafana Error rate". Each answers with a token once; keep each one.
2. Make sure Settle has services named `payments`, `checkout`, and `search` (case does not matter):
   an alert for a service Settle does not know is refused.
3. Create the three checks from `checks/synthetic-checks.yaml`, with the demo shop's deployed URL.
4. Create the template and the three contact points from `contact-points/webhook-contact-point.yaml`
   (Alerting > Contact points > Notification templates, then each webhook contact point with
   **Custom Payload** set to `{{ template "settle.alert" . }}`). Use Settle's backend URL and one
   token per contact point in place of the `${...}` values. Never commit a token.
5. Create the rules from `alerts/alert-rules.yaml`, then the notification policy. Importing
   `policies` replaces the whole policy tree.
6. Check each case in `docs/4-testing/test-cases.md` (TC-D36a to f) with the demo shop's fault
   switches, and attach a screenshot of each to the pull request.

The files use Grafana's file-provisioning format, which is also what Alerting's export produces.
`grafanacloud-prom` is the stack's Prometheus data source; change the UID if yours differs. After
setting up, export the rules, contact points, and checks from Grafana Cloud and replace these
files, so the repository holds what actually runs.
