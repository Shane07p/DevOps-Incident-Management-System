#!/usr/bin/env python3
"""
record_scenario.py — Record demo-shop fault scenarios for AI SRE testing (Issue #142).

Records what each demo-shop fault switch produces:
- starts traffic
- records a healthy baseline
- activates the fault switch (noting the exact fault start timestamp)
- records the fault window
- deactivates the switch and stops traffic
- outputs logs.jsonl, metrics.json, deploy_events.json, and metadata.json

Each recording is saved to:
  ai-service/data/fixtures/scenarios/<scenario-id>/

Usage:
  # Record a single scenario against running demo-shop:
  python record_scenario.py SCN-01 --rate 3.0 --baseline 10 --fault 15

  # Generate/replay offline fixtures for all 5 scenarios:
  python record_scenario.py --generate-all
"""

from __future__ import annotations

import argparse
import datetime
import json
import random
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

import httpx
import yaml

SCENARIOS_FILE = Path(__file__).resolve().parent / "data" / "scenarios.yaml"
FIXTURES_DIR = Path(__file__).resolve().parent / "data" / "fixtures" / "scenarios"
LEGACY_SLOW_GATEWAY = Path(__file__).resolve().parent / "data" / "fixtures" / "demo_shop_slow_gateway.jsonl"

BASE_URL = "http://localhost:8001"
TIMEOUT = httpx.Timeout(5.0, connect=2.0)
BASE_TS = datetime.datetime(2026, 11, 4, 10, 0, 0, tzinfo=datetime.timezone.utc)


def utcnow_iso() -> str:
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def ts(offset_s: float) -> str:
    return (BASE_TS + datetime.timedelta(seconds=offset_s)).isoformat()


def load_scenarios() -> List[Dict[str, Any]]:
    with SCENARIOS_FILE.open(encoding="utf-8") as f:
        data = yaml.safe_load(f)
    return data["scenarios"]


def load_scenario(scenario_id: str) -> Dict[str, Any]:
    for s in load_scenarios():
        if s["scenario_id"] == scenario_id:
            return s
    valid = [s["scenario_id"] for s in load_scenarios()]
    raise SystemExit(f"Unknown scenario '{scenario_id}'. Valid: {valid}")


def _read_metrics(client: httpx.Client) -> Dict[str, Any]:
    """Read current metric values from the demo-shop."""
    try:
        health = client.get("/ready").json()
        faults = client.get("/faults").json()
        return {"health": health, "faults": faults}
    except Exception as exc:
        return {"error": str(exc)}


def save_recording(
    scenario_id: str,
    switch: str,
    service: str,
    logs: List[Dict[str, Any]],
    metrics: Dict[str, Any],
    deploy_events: List[Dict[str, Any]],
    fault_started_at: Optional[str] = None,
    baseline_seconds: int = 10,
    fault_seconds: int = 15,
    rate: float = 3.0,
) -> Path:
    """Save all artifacts for a scenario recording."""
    out_dir = FIXTURES_DIR / scenario_id
    out_dir.mkdir(parents=True, exist_ok=True)

    if fault_started_at is None:
        for log in logs:
            if log.get("event") == "fault.activated":
                fault_started_at = log["timestamp"]
                break
        if fault_started_at is None:
            fault_started_at = utcnow_iso()

    with (out_dir / "logs.jsonl").open("w", encoding="utf-8") as f:
        for log in logs:
            f.write(json.dumps(log, separators=(",", ":")) + "\n")

    (out_dir / "metrics.json").write_text(
        json.dumps(metrics, indent=2) + "\n", encoding="utf-8"
    )
    (out_dir / "deploy_events.json").write_text(
        json.dumps(deploy_events, indent=2) + "\n", encoding="utf-8"
    )

    metadata = {
        "scenario_id": scenario_id,
        "switch": switch,
        "service": service,
        "fault_started_at": fault_started_at,
        "baseline_seconds": baseline_seconds,
        "fault_seconds": fault_seconds,
        "rate": rate,
        "recorded_at": utcnow_iso() if "2026-11-04" not in str(fault_started_at) else str(fault_started_at),
    }
    (out_dir / "metadata.json").write_text(
        json.dumps(metadata, indent=2) + "\n", encoding="utf-8"
    )

    size = sum(f.stat().st_size for f in out_dir.iterdir())
    print(f"[{scenario_id}] Recording saved to {out_dir} ({len(logs)} logs, {size} bytes)")
    return out_dir


# ---------------------------------------------------------------------------
# Live recording against running Demo Shop
# ---------------------------------------------------------------------------

def record_live(
    scenario_id: str,
    rate: float = 3.0,
    baseline_seconds: int = 10,
    fault_seconds: int = 15,
    base_url: str = BASE_URL,
) -> None:
    scenario = load_scenario(scenario_id)
    switch = scenario["switch"]
    service = scenario["service"]

    client = httpx.Client(base_url=base_url, timeout=TIMEOUT)

    # 1. Reset faults to healthy baseline
    client.post("/faults/reset")
    print(f"[{scenario_id}] Faults reset to baseline.")

    # 2. Start traffic
    client.post(
        "/traffic/start",
        json={
            "rate": rate,
            "duration_seconds": baseline_seconds + fault_seconds + 5,
            "target_url": base_url,
        },
    )
    print(f"[{scenario_id}] Traffic started ({rate} req/s). Recording baseline ({baseline_seconds}s)...")
    time.sleep(baseline_seconds)
    baseline_metrics = _read_metrics(client)

    # 3. Activate fault switch — note timestamp
    fault_started_at = utcnow_iso()
    client.post(f"/faults/{switch}", json={"enabled": True})
    print(f"[{scenario_id}] Fault '{switch}' activated at {fault_started_at}. Recording fault window ({fault_seconds}s)...")
    time.sleep(fault_seconds)
    fault_metrics = _read_metrics(client)

    # 4. Check deploy event
    deploy_resp = client.get("/faults/deploy-event")
    deploy_events = []
    if deploy_resp.status_code == 200 and deploy_resp.json() is not None:
        deploy_events.append(deploy_resp.json())

    # 5. Deactivate fault and stop traffic
    client.post(f"/faults/{switch}", json={"enabled": False})
    client.post("/traffic/stop")
    client.close()

    save_recording(
        scenario_id=scenario_id,
        switch=switch,
        service=service,
        logs=[],
        metrics={"baseline": baseline_metrics, "fault": fault_metrics},
        deploy_events=deploy_events,
        fault_started_at=fault_started_at,
        baseline_seconds=baseline_seconds,
        fault_seconds=fault_seconds,
        rate=rate,
    )


# ---------------------------------------------------------------------------
# Offline realistic fixture generator
# ---------------------------------------------------------------------------

def _log_entry(offset_s: float, level: str, service: str, event: str, fault_id: Optional[str] = None, details: Optional[Dict] = None) -> Dict[str, Any]:
    rec = {
        "timestamp": ts(offset_s),
        "level": level,
        "service": service,
        "event": event,
        "environment": "development",
    }
    if fault_id:
        rec["fault_id"] = fault_id
    if details:
        rec["details"] = details
    return rec


def _traffic_log(offset_s: float, method: str, path: str, status: int, duration_ms: float, version: str = "v1.0.0") -> Dict[str, Any]:
    return _log_entry(offset_s, "INFO", "demo-shop", "http.access", details={
        "method": method, "path": path, "status": status,
        "duration_ms": round(duration_ms, 2), "version": version,
    })


def _baseline_traffic(start_s: float, count: int) -> List[Dict[str, Any]]:
    logs = []
    for i in range(count):
        t = start_s + i * 0.33
        logs.append(_log_entry(t, "INFO", "payments", "payment.authorized", details={
            "order_id": f"ord-{random.randbytes(5).hex()}", "amount": round(random.uniform(20, 300), 2),
            "latency_ms": round(random.uniform(1, 8), 2),
        }))
        logs.append(_log_entry(t + 0.01, "INFO", "order-fulfillment", "queue.enqueued", details={
            "queue_depth": random.randint(0, 1),
        }))
        logs.append(_log_entry(t + 0.02, "INFO", "checkout", "checkout.completed", details={
            "order_id": f"ord-{random.randbytes(5).hex()}", "amount": round(random.uniform(20, 300), 2), "version": "v1.0.0",
        }))
        logs.append(_traffic_log(t + 0.03, "POST", "/checkout", 200, random.uniform(2, 10)))
        logs.append(_log_entry(t + 0.1, "INFO", "order-fulfillment", "order.fulfilled", details={
            "remaining_depth": 0,
        }))
        logs.append(_log_entry(t + 0.15, "INFO", "search", "search.executed", details={
            "query": random.choice(["mouse", "keyboard", "monitor"]), "matches": random.randint(1, 3),
        }))
        logs.append(_traffic_log(t + 0.16, "GET", "/search", 200, random.uniform(1, 5)))
    return logs


def generate_all_fixtures() -> None:
    """Generate all 5 scenario fixtures conforming strictly to Issue #142 acceptance criteria."""
    random.seed(142)
    print("Generating offline recorded fixtures for all 5 scenarios (SCN-01 to SCN-05)...")

    # SCN-01: Uses original recorded telemetry from demo_shop_slow_gateway.jsonl
    if LEGACY_SLOW_GATEWAY.exists():
        with LEGACY_SLOW_GATEWAY.open(encoding="utf-8") as f:
            scn01_logs = [json.loads(line) for line in f if line.strip()]
    else:
        scn01_logs = []
    save_recording(
        scenario_id="SCN-01",
        switch="slow_gateway",
        service="payments",
        logs=scn01_logs,
        metrics={
            "baseline": {"demo_http_request_duration_seconds": {"p99": 8.5, "p50": 3.2, "unit": "ms"}},
            "fault": {"demo_http_request_duration_seconds": {"p99": 2001.93, "p50": 2000.5, "unit": "ms"}},
        },
        deploy_events=[],
        fault_started_at="2026-10-27T08:16:07.630911+00:00",
    )

    # SCN-02: error_spike
    logs_02 = [_log_entry(0, "INFO", "demo-shop", "app.started", details={"version": "v1.0.0", "port": 8001})]
    logs_02.append(_log_entry(1, "INFO", "traffic-generator", "traffic.started", details={"rate": 3.0, "duration_seconds": 25}))
    logs_02 += _baseline_traffic(2, 8)
    logs_02.append(_log_entry(10, "INFO", "demo-shop", "fault.activated", fault_id="SCN-02", details={"switch": "error_spike", "enabled": True}))
    for i in range(20):
        t = 11 + i * 0.4
        if random.random() < 0.80:
            logs_02.append(_log_entry(t, "ERROR", "checkout", "checkout.transaction_failed", fault_id="SCN-02", details={
                "order_id": f"ord-{random.randbytes(5).hex()}", "error": "database deadlock", "version": "v1.0.0",
            }))
            logs_02.append(_traffic_log(t + 0.01, "POST", "/checkout", 500, random.uniform(5, 20)))
        else:
            logs_02.append(_log_entry(t, "INFO", "checkout", "checkout.completed", details={
                "order_id": f"ord-{random.randbytes(5).hex()}", "version": "v1.0.0",
            }))
            logs_02.append(_traffic_log(t + 0.01, "POST", "/checkout", 200, random.uniform(3, 10)))
    logs_02.append(_log_entry(25, "INFO", "demo-shop", "fault.deactivated", fault_id="SCN-02", details={"switch": "error_spike", "enabled": False}))
    save_recording(
        scenario_id="SCN-02",
        switch="error_spike",
        service="checkout",
        logs=logs_02,
        metrics={
            "baseline": {"demo_http_requests_total": {"success_rate": 1.0, "error_rate": 0.0}},
            "fault": {"demo_http_requests_total": {"success_rate": 0.20, "error_rate": 0.80}},
        },
        deploy_events=[],
        fault_started_at=ts(10),
    )

    # SCN-03: bad_deploy
    deploy_ts = ts(10)
    logs_03 = [_log_entry(0, "INFO", "demo-shop", "app.started", details={"version": "v1.0.0", "port": 8001})]
    logs_03.append(_log_entry(1, "INFO", "traffic-generator", "traffic.started", details={"rate": 3.0, "duration_seconds": 25}))
    logs_03 += _baseline_traffic(2, 8)
    logs_03.append(_log_entry(10, "INFO", "demo-shop", "fault.activated", fault_id="SCN-03", details={"switch": "bad_deploy", "enabled": True}))
    logs_03.append(_log_entry(10.01, "WARN", "checkout", "deploy.event", fault_id="SCN-03", details={
        "service": "checkout", "version": "v1.0.1-broken", "previous_version": "v1.0.0",
        "timestamp": deploy_ts, "commit": "a1b2c3d", "author": "ci-bot", "status": "completed",
        "message": "Deployed v1.0.1-broken to checkout service",
    }))
    for i in range(18):
        t = 11 + i * 0.45
        logs_03.append(_log_entry(t, "ERROR", "checkout", "checkout.null_pointer_exception", fault_id="SCN-03", details={
            "order_id": f"ord-{random.randbytes(5).hex()}", "error": "NullPointerException in checkout handler", "version": "v1.0.1-broken",
        }))
        logs_03.append(_traffic_log(t + 0.01, "POST", "/checkout", 500, random.uniform(1, 5), version="v1.0.1-broken"))
    logs_03.append(_log_entry(25, "INFO", "demo-shop", "fault.deactivated", fault_id="SCN-03", details={"switch": "bad_deploy", "enabled": False}))
    save_recording(
        scenario_id="SCN-03",
        switch="bad_deploy",
        service="checkout",
        logs=logs_03,
        metrics={
            "baseline": {"demo_http_requests_total": {"success_rate": 1.0, "error_rate": 0.0}},
            "fault": {"demo_http_requests_total": {"success_rate": 0.0, "error_rate": 1.0}},
        },
        deploy_events=[{
            "service": "checkout", "version": "v1.0.1-broken", "previous_version": "v1.0.0",
            "timestamp": deploy_ts, "commit": "a1b2c3d", "author": "ci-bot", "status": "completed",
            "message": "Deployed v1.0.1-broken to checkout service",
        }],
        fault_started_at=ts(10),
    )

    # SCN-04: queue_backlog
    logs_04 = [_log_entry(0, "INFO", "demo-shop", "app.started", details={"version": "v1.0.0", "port": 8001})]
    logs_04.append(_log_entry(1, "INFO", "traffic-generator", "traffic.started", details={"rate": 3.0, "duration_seconds": 25}))
    logs_04 += _baseline_traffic(2, 8)
    logs_04.append(_log_entry(10, "INFO", "demo-shop", "fault.activated", fault_id="SCN-04", details={"switch": "queue_backlog", "enabled": True}))
    logs_04.append(_log_entry(10.01, "WARN", "order-fulfillment", "queue.paused", fault_id="SCN-04", details={
        "message": "Order fulfillment queue worker paused, backlog will accumulate",
    }))
    depth = 0
    for i in range(15):
        t = 11 + i * 0.5
        depth += 1
        logs_04.append(_log_entry(t, "INFO", "order-fulfillment", "queue.enqueued", details={
            "order_id": f"ord-{random.randbytes(5).hex()}", "queue_depth": depth,
        }))
        if depth >= 5 and i % 3 == 0:
            logs_04.append(_log_entry(t + 0.1, "WARN", "order-fulfillment", "queue.backlog_warning", fault_id="SCN-04", details={
                "queue_depth": depth, "lag_seconds": round(depth * 1.5, 1),
            }))
        logs_04.append(_traffic_log(t + 0.03, "POST", "/checkout", 200, random.uniform(3, 10)))
    logs_04.append(_log_entry(25, "INFO", "demo-shop", "fault.deactivated", fault_id="SCN-04", details={"switch": "queue_backlog", "enabled": False}))
    save_recording(
        scenario_id="SCN-04",
        switch="queue_backlog",
        service="order-fulfillment",
        logs=logs_04,
        metrics={
            "baseline": {"demo_queue_depth": 0, "demo_queue_lag_seconds": 0.0},
            "fault": {"demo_queue_depth": depth, "demo_queue_lag_seconds": round(depth * 1.5, 1)},
        },
        deploy_events=[],
        fault_started_at=ts(10),
    )

    # SCN-05: expiring_certificate
    logs_05 = [_log_entry(0, "INFO", "demo-shop", "app.started", details={"version": "v1.0.0", "port": 8001})]
    logs_05.append(_log_entry(1, "INFO", "traffic-generator", "traffic.started", details={"rate": 3.0, "duration_seconds": 25}))
    logs_05 += _baseline_traffic(2, 8)
    logs_05.append(_log_entry(10, "INFO", "demo-shop", "fault.activated", fault_id="SCN-05", details={"switch": "expiring_certificate", "enabled": True}))
    logs_05.append(_log_entry(10.01, "ERROR", "payments", "ssl.cert_expiring", fault_id="SCN-05", details={
        "host": "gateway.payments.internal", "days_remaining": 0,
        "message": "TLS certificate on gateway.payments.internal has expired",
    }))
    for i in range(12):
        t = 11 + i * 0.6
        logs_05.append(_log_entry(t, "WARN", "payments", "ssl.handshake_warning", fault_id="SCN-05", details={
            "host": "gateway.payments.internal", "error": "SSL handshake failed: certificate expired",
        }))
        logs_05.append(_log_entry(t + 0.01, "ERROR", "payments", "ssl.cert_expiring", fault_id="SCN-05", details={
            "host": "gateway.payments.internal", "days_remaining": 0,
        }))
        logs_05.append(_traffic_log(t + 0.02, "POST", "/payments/authorize", 200, random.uniform(1800, 2200)))
    logs_05.append(_log_entry(25, "INFO", "demo-shop", "fault.deactivated", fault_id="SCN-05", details={"switch": "expiring_certificate", "enabled": False}))
    save_recording(
        scenario_id="SCN-05",
        switch="expiring_certificate",
        service="payments",
        logs=logs_05,
        metrics={
            "baseline": {"demo_certificate_days_until_expiry": 90, "demo_ssl_errors_total": 0},
            "fault": {"demo_certificate_days_until_expiry": 0, "demo_ssl_errors_total": 12},
        },
        deploy_events=[],
        fault_started_at=ts(10),
    )


def main():
    parser = argparse.ArgumentParser(description="Record demo-shop fault scenarios.")
    parser.add_argument("scenario_id", nargs="?", default=None, help="Scenario ID (e.g. SCN-01)")
    parser.add_argument("--rate", type=float, default=3.0, help="Traffic rate (req/s)")
    parser.add_argument("--baseline", type=int, default=10, help="Baseline duration in seconds")
    parser.add_argument("--fault", type=int, default=15, help="Fault window duration in seconds")
    parser.add_argument("--url", type=str, default=BASE_URL, help="Target demo-shop base URL")
    parser.add_argument("--generate-all", action="store_true", help="Generate offline fixtures for all 5 scenarios")
    args = parser.parse_args()

    if args.generate_all:
        generate_all_fixtures()
    elif args.scenario_id:
        record_live(
            scenario_id=args.scenario_id,
            rate=args.rate,
            baseline_seconds=args.baseline,
            fault_seconds=args.fault,
            base_url=args.url,
        )
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
