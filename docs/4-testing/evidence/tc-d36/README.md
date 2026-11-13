# TC-D36: screenshots of the run on 13 November 2026

The Grafana Cloud rules from `grafana/` (`cec3c87`), checks from Singapore and Mumbai, the demo shop
on Render, and production Settle. Times are IST. The cases are in
[test-cases.md](../../test-cases.md); the results are in [test-report.md](../../test-report.md)
(Sprint 3).

## `bad_deploy` on at 20:17: every checkout answers 500

| Screenshot | Shows | Case |
| --- | --- | --- |
| [01](01-b-checkout-down-pending.png) | Checkout is down Pending after the first failing check; the other rules Normal | b |
| [02](02-c-checkout-down-firing.png) | Checkout is down Firing after the second failing check in a row | c |
| [03](03-c-settle-inc-1.png) | Settle opened INC-1, Checkout is down, SEV1, at 20:23 | c |
| [04](04-c-checkout-rules-firing.png) | Checkout error rate Firing as well | c |
| [05](05-c-settle-inc-1-and-2.png) | INC-2, Checkout error rate above 5%, SEV2, at 20:24 | c |
| [06](06-f-still-firing-2031.png) | Both checkout rules still Firing at 20:31 | f |
| [07](07-f-alerts-counted-once.png) | Settle's Alerts page: each Grafana alert counted once, still INC-1 and INC-2 only | f |
| [08](08-inc-1-timeline.png) | INC-1's timeline after the fault was reset: created from the grafana-down alert, then acknowledged, resolved with a note, and closed by hand | |

## `slow_gateway` on at 20:43: payments, and checkout through it, take 2 s longer

| Screenshot | Shows | Case |
| --- | --- | --- |
| [09](09-d-payments-slow-pending.png) | Payments is slow Pending after its first slow checks; Checkout is slow already Firing, as its check runs every 2 minutes | d |
| [10](10-d-settle-inc-3.png) | Settle opened INC-3, Checkout is slow, SEV2, at 20:51; nothing for payments yet | d |
| [11](11-e-payments-slow-firing.png) | Payments is slow Firing after the third slow check in a row | e |
| [12](12-e-settle-inc-4.png) | Settle opened INC-4, Payments is slow, SEV2, at 20:57 | e |

TC-D36a (1 of 2 locations failing) has no screenshot: a fault switch fails every location at once,
so it was checked in a local Grafana on 12 November instead (see the report).
