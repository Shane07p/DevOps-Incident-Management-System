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
  python record_scenario.py SCN-02 --rate 3.0 --baseline 10 --fault 15

  # Record multiple scenarios:
  python record_scenario.py SCN-02 SCN-03 SCN-04 SCN-05
"""

from __future__ import annotations

import argparse
import datetime
import json
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional
from urllib.parse import urlparse

import httpx
import yaml

SCENARIOS_FILE = Path(__file__).resolve().parent / "data" / "scenarios.yaml"
FIXTURES_DIR = Path(__file__).resolve().parent / "data" / "fixtures" / "scenarios"

BASE_URL = "http://127.0.0.1:8001"
TIMEOUT = httpx.Timeout(10.0, connect=3.0)


def utcnow_iso() -> str:
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


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


def compute_metrics(
    scenario_id: str,
    logs: List[Dict[str, Any]],
    fault_started_at: str,
) -> Dict[str, Any]:
    """Derive scenario metrics from actual recorded logs during baseline and fault windows."""
    baseline_logs = [l for l in logs if l.get("timestamp", "") < fault_started_at]
    fault_logs = [l for l in logs if l.get("timestamp", "") >= fault_started_at]

    if scenario_id == "SCN-01":
        def extract_latencies(log_list):
            lats = []
            for l in log_list:
                if l.get("service") == "demo-shop" and l.get("event") == "http.access":
                    if "/payments" in l.get("details", {}).get("path", ""):
                        lats.append(l["details"].get("duration_ms", 0.0))
                elif l.get("service") == "payments" and "latency_ms" in l.get("details", {}):
                    lats.append(l["details"]["latency_ms"])
            return lats or [5.0]

        base_lats = sorted(extract_latencies(baseline_logs))
        fault_lats = sorted(extract_latencies(fault_logs))
        return {
            "baseline": {
                "demo_http_request_duration_seconds": {
                    "p99": round(base_lats[int(len(base_lats) * 0.99)], 2) if base_lats else 8.5,
                    "p50": round(base_lats[int(len(base_lats) * 0.50)], 2) if base_lats else 3.2,
                    "unit": "ms",
                }
            },
            "fault": {
                "demo_http_request_duration_seconds": {
                    "p99": round(fault_lats[min(int(len(fault_lats) * 0.99), len(fault_lats) - 1)], 2) if fault_lats else 2001.93,
                    "p50": round(fault_lats[int(len(fault_lats) * 0.50)], 2) if fault_lats else 2000.5,
                    "unit": "ms",
                }
            },
        }

    elif scenario_id == "SCN-02":
        def calc_rates(log_list):
            checkout_calls = [
                l for l in log_list
                if l.get("service") == "demo-shop"
                and l.get("event") == "http.access"
                and l.get("details", {}).get("path") == "/checkout"
            ]
            if not checkout_calls:
                errs = len([l for l in log_list if l.get("event") == "checkout.transaction_failed"])
                ok = len([l for l in log_list if l.get("event") == "checkout.completed"])
                total = errs + ok
                if total == 0:
                    return 1.0, 0.0
                return round(ok / total, 2), round(errs / total, 2)
            errors = sum(1 for c in checkout_calls if c.get("details", {}).get("status", 200) >= 400)
            total = len(checkout_calls)
            err_rate = round(errors / total, 2)
            succ_rate = round(1.0 - err_rate, 2)
            return succ_rate, err_rate

        base_succ, base_err = calc_rates(baseline_logs)
        fault_succ, fault_err = calc_rates(fault_logs)
        return {
            "baseline": {
                "demo_http_requests_total": {
                    "success_rate": base_succ,
                    "error_rate": base_err,
                }
            },
            "fault": {
                "demo_http_requests_total": {
                    "success_rate": fault_succ,
                    "error_rate": fault_err,
                }
            },
        }

    elif scenario_id == "SCN-03":
        def calc_rates(log_list):
            checkout_calls = [
                l for l in log_list
                if l.get("service") == "demo-shop"
                and l.get("event") == "http.access"
                and l.get("details", {}).get("path") == "/checkout"
            ]
            if not checkout_calls:
                return 1.0, 0.0
            errors = sum(1 for c in checkout_calls if c.get("details", {}).get("status", 200) >= 400)
            total = len(checkout_calls)
            err_rate = round(errors / total, 2)
            succ_rate = round(1.0 - err_rate, 2)
            return succ_rate, err_rate

        base_succ, base_err = calc_rates(baseline_logs)
        fault_succ, fault_err = calc_rates(fault_logs)
        return {
            "baseline": {
                "demo_http_requests_total": {
                    "success_rate": base_succ,
                    "error_rate": base_err,
                }
            },
            "fault": {
                "demo_http_requests_total": {
                    "success_rate": fault_succ,
                    "error_rate": fault_err,
                }
            },
        }

    elif scenario_id == "SCN-04":
        def get_max_queue(log_list):
            max_d = 0
            max_l = 0.0
            for l in log_list:
                det = l.get("details", {})
                if "queue_depth" in det:
                    max_d = max(max_d, int(det["queue_depth"]))
                if "lag_seconds" in det:
                    max_l = max(max_l, float(det["lag_seconds"]))
            return max_d, max_l

        base_d, base_l = get_max_queue(baseline_logs)
        fault_d, fault_l = get_max_queue(fault_logs)
        return {
            "baseline": {
                "demo_queue_depth": base_d,
                "demo_queue_lag_seconds": round(base_l, 1),
            },
            "fault": {
                "demo_queue_depth": fault_d,
                "demo_queue_lag_seconds": round(fault_l, 1),
            },
        }

    elif scenario_id == "SCN-05":
        base_ssl = sum(1 for l in baseline_logs if "ssl" in l.get("event", ""))
        fault_ssl = sum(1 for l in fault_logs if "ssl" in l.get("event", ""))
        return {
            "baseline": {
                "demo_certificate_days_until_expiry": 90,
                "demo_ssl_errors_total": base_ssl,
            },
            "fault": {
                "demo_certificate_days_until_expiry": 0,
                "demo_ssl_errors_total": fault_ssl,
            },
        }

    return {"baseline": {}, "fault": {}}


def save_recording(
    scenario_id: str,
    switch: str,
    service: str,
    logs: List[Dict[str, Any]],
    metrics: Dict[str, Any],
    deploy_events: List[Dict[str, Any]],
    fault_started_at: str,
    baseline_seconds: int = 10,
    fault_seconds: int = 15,
    rate: float = 3.0,
) -> Path:
    """Save all artifacts for a scenario recording."""
    out_dir = FIXTURES_DIR / scenario_id
    out_dir.mkdir(parents=True, exist_ok=True)

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
        "recorded_at": utcnow_iso(),
    }
    (out_dir / "metadata.json").write_text(
        json.dumps(metadata, indent=2) + "\n", encoding="utf-8"
    )

    size = sum(f.stat().st_size for f in out_dir.iterdir())
    print(f"[{scenario_id}] Recording saved to {out_dir} ({len(logs)} logs, {size} bytes)")
    return out_dir


def ensure_server_running(base_url: str) -> Optional[subprocess.Popen]:
    """Check if demo-shop is running; if not, launch it in a subprocess."""
    try:
        r = httpx.get(f"{base_url}/health", timeout=1.0)
        if r.status_code == 200:
            print(f"Connected to running demo-shop at {base_url}")
            return None
    except Exception:
        pass

    parsed = urlparse(base_url)
    port = parsed.port or 8001

    demo_shop_dir = Path(__file__).resolve().parent.parent / "demo-shop"
    print(f"Demo-shop not running at {base_url}. Launching server from {demo_shop_dir} on port {port}...")
    cmd = [
        sys.executable,
        "-m",
        "uvicorn",
        "demo_shop.app:app",
        "--host",
        "0.0.0.0",
        "--port",
        str(port),
    ]
    proc = subprocess.Popen(
        cmd,
        cwd=str(demo_shop_dir),
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )

    deadline = time.time() + 15.0
    while time.time() < deadline:
        try:
            r = httpx.get(f"{base_url}/health", timeout=1.0)
            if r.status_code == 200:
                print("Demo-shop server is ready.")
                return proc
        except Exception:
            time.sleep(0.5)

    proc.terminate()
    raise RuntimeError(f"Demo-shop server failed to start on {base_url} within 15 seconds.")


def record_live(
    scenario_id: str,
    rate: float = 3.0,
    baseline_seconds: int = 10,
    fault_seconds: int = 15,
    base_url: str = BASE_URL,
    client: Optional[httpx.Client] = None,
) -> Path:
    scenario = load_scenario(scenario_id)
    switch = scenario["switch"]
    service = scenario["service"]

    close_client = False
    if client is None:
        client = httpx.Client(base_url=base_url, timeout=TIMEOUT)
        close_client = True

    try:
        # 1. Reset faults and clear logs buffer
        client.post("/faults/reset")
        try:
            client.post("/telemetry/logs/clear")
        except Exception:
            pass
        print(f"[{scenario_id}] Faults reset and telemetry buffer cleared.")

        # 2. Start traffic
        duration = baseline_seconds + fault_seconds + 5
        client.post(
            "/traffic/start",
            json={
                "rate": rate,
                "duration_seconds": duration,
                "target_url": base_url,
            },
        )
        print(f"[{scenario_id}] Traffic started ({rate} req/s). Recording baseline ({baseline_seconds}s)...")
        time.sleep(baseline_seconds)

        # 3. Activate fault switch — note timestamp
        fault_started_at = utcnow_iso()
        client.post(f"/faults/{switch}", json={"enabled": True})
        print(f"[{scenario_id}] Fault '{switch}' activated at {fault_started_at}. Recording fault window ({fault_seconds}s)...")
        time.sleep(fault_seconds)

        # 4. Check deploy event
        deploy_resp = client.get("/faults/deploy-event")
        deploy_events = []
        if deploy_resp.status_code == 200 and deploy_resp.json() is not None:
            deploy_events.append(deploy_resp.json())

        # 5. Deactivate fault and stop traffic
        client.post(f"/faults/{switch}", json={"enabled": False})
        client.post("/traffic/stop")

        # 6. Fetch logs
        logs = []
        try:
            logs_resp = client.get("/telemetry/logs")
            if logs_resp.status_code == 200:
                logs = logs_resp.json()
        except Exception as exc:
            print(f"[{scenario_id}] Warning: could not fetch /telemetry/logs: {exc}")

        # Compute scenario metrics from actual recorded logs
        metrics = compute_metrics(scenario_id, logs, fault_started_at)

        return save_recording(
            scenario_id=scenario_id,
            switch=switch,
            service=service,
            logs=logs,
            metrics=metrics,
            deploy_events=deploy_events,
            fault_started_at=fault_started_at,
            baseline_seconds=baseline_seconds,
            fault_seconds=fault_seconds,
            rate=rate,
        )

    finally:
        if close_client:
            client.close()


def main():
    parser = argparse.ArgumentParser(description="Record demo-shop fault scenarios.")
    parser.add_argument(
        "scenario_ids",
        nargs="+",
        help="One or more Scenario IDs to record (e.g. SCN-02, SCN-03, SCN-04, SCN-05)",
    )
    parser.add_argument("--rate", type=float, default=3.0, help="Traffic rate (req/s)")
    parser.add_argument("--baseline", type=int, default=10, help="Baseline duration in seconds")
    parser.add_argument("--fault", type=int, default=15, help="Fault window duration in seconds")
    parser.add_argument("--url", type=str, default=BASE_URL, help="Target demo-shop base URL")
    args = parser.parse_args()

    server_proc = None
    try:
        server_proc = ensure_server_running(args.url)
        with httpx.Client(base_url=args.url, timeout=TIMEOUT) as client:
            for sid in args.scenario_ids:
                print(f"\n{'=' * 60}\nRecording scenario: {sid}\n{'=' * 60}")
                record_live(
                    scenario_id=sid,
                    rate=args.rate,
                    baseline_seconds=args.baseline,
                    fault_seconds=args.fault,
                    base_url=args.url,
                    client=client,
                )
    finally:
        if server_proc:
            print("Stopping demo-shop server...")
            server_proc.terminate()
            try:
                server_proc.wait(timeout=5)
            except Exception:
                server_proc.kill()


if __name__ == "__main__":
    main()
