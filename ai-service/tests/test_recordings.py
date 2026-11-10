"""Tests for Issue #142: every scenario in scenarios.yaml has a recording,
and each recording's fault window shows the change its row expects."""

import json
from pathlib import Path

import pytest
import yaml


SCENARIOS_FILE = (
    Path(__file__).resolve().parents[1] / "data" / "scenarios.yaml"
)
FIXTURES_DIR = (
    Path(__file__).resolve().parents[1] / "data" / "fixtures" / "scenarios"
)


def load_scenarios():
    with SCENARIOS_FILE.open(encoding="utf-8") as f:
        return yaml.safe_load(f)["scenarios"]


def load_logs(scenario_id):
    log_file = FIXTURES_DIR / scenario_id / "logs.jsonl"
    assert log_file.exists(), f"Missing logs.jsonl for {scenario_id}"
    with log_file.open(encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def load_json(scenario_id, filename):
    path = FIXTURES_DIR / scenario_id / filename
    assert path.exists(), f"Missing {filename} for {scenario_id}"
    with path.open(encoding="utf-8") as f:
        return json.load(f)


# ──────────────────────────────────────────────────────────────────
# Test 1: every scenario has a recording directory with all files
# ──────────────────────────────────────────────────────────────────

def test_every_scenario_has_a_recording():
    scenarios = load_scenarios()
    for s in scenarios:
        sid = s["scenario_id"]
        scenario_dir = FIXTURES_DIR / sid
        assert scenario_dir.is_dir(), f"No recording directory for {sid}"

        for filename in ("logs.jsonl", "metrics.json", "deploy_events.json", "metadata.json"):
            assert (scenario_dir / filename).exists(), f"Missing {filename} in {sid}"


# ──────────────────────────────────────────────────────────────────
# Test 2: metadata matches scenarios.yaml
# ──────────────────────────────────────────────────────────────────

def test_metadata_matches_scenario_definition():
    scenarios = load_scenarios()
    for s in scenarios:
        sid = s["scenario_id"]
        meta = load_json(sid, "metadata.json")

        assert meta["scenario_id"] == sid
        assert meta["switch"] == s["switch"]
        assert meta["service"] == s["service"]
        assert meta["fault_started_at"] is not None


# ──────────────────────────────────────────────────────────────────
# Test 3: each recording is small enough to commit (under 1 MB)
# ──────────────────────────────────────────────────────────────────

def test_recordings_are_under_one_megabyte():
    scenarios = load_scenarios()
    for s in scenarios:
        sid = s["scenario_id"]
        scenario_dir = FIXTURES_DIR / sid
        total = sum(f.stat().st_size for f in scenario_dir.iterdir())
        assert total < 1_000_000, f"{sid} recording is {total} bytes (> 1 MB)"


# ──────────────────────────────────────────────────────────────────
# Test 4: fault window shows expected log patterns
# ──────────────────────────────────────────────────────────────────

@pytest.mark.parametrize("scenario_id,expected_patterns", [
    ("SCN-01", ["payment.gateway.slow"]),
    ("SCN-02", ["checkout.transaction_failed"]),
    ("SCN-03", ["checkout.null_pointer_exception", "deploy.event"]),
    ("SCN-04", ["queue.paused", "queue.backlog_warning"]),
    ("SCN-05", ["ssl.cert_expiring", "ssl.handshake_warning"]),
])
def test_fault_window_contains_expected_log_patterns(scenario_id, expected_patterns):
    logs = load_logs(scenario_id)
    meta = load_json(scenario_id, "metadata.json")
    fault_start = meta["fault_started_at"]

    # Collect events that appear at or after the fault start
    fault_events = set()
    after_fault = False
    for log in logs:
        if log.get("event") == "fault.activated":
            after_fault = True
        if after_fault:
            fault_events.add(log.get("event", ""))

    for pattern in expected_patterns:
        assert pattern in fault_events, (
            f"{scenario_id}: expected log pattern '{pattern}' not found in fault window. "
            f"Found events: {sorted(fault_events)}"
        )


# ──────────────────────────────────────────────────────────────────
# Test 5: SCN-01 latency rises above baseline
# ──────────────────────────────────────────────────────────────────

def test_scn01_latency_rises():
    metrics = load_json("SCN-01", "metrics.json")
    baseline_p99 = metrics["baseline"]["demo_http_request_duration_seconds"]["p99"]
    fault_p99 = metrics["fault"]["demo_http_request_duration_seconds"]["p99"]
    assert fault_p99 > baseline_p99 * 10, (
        f"SCN-01: fault latency {fault_p99}ms should be >> baseline {baseline_p99}ms"
    )


# ──────────────────────────────────────────────────────────────────
# Test 6: SCN-02 error rate rises above baseline
# ──────────────────────────────────────────────────────────────────

def test_scn02_error_rate_rises():
    metrics = load_json("SCN-02", "metrics.json")
    baseline_err = metrics["baseline"]["demo_http_requests_total"]["error_rate"]
    fault_err = metrics["fault"]["demo_http_requests_total"]["error_rate"]
    assert fault_err > 0.5, f"SCN-02: fault error rate {fault_err} should be > 50%"
    assert fault_err > baseline_err


# ──────────────────────────────────────────────────────────────────
# Test 7: SCN-03 has a deploy event
# ──────────────────────────────────────────────────────────────────

def test_scn03_has_deploy_event():
    deploys = load_json("SCN-03", "deploy_events.json")
    assert len(deploys) >= 1, "SCN-03 should have at least one deploy event"
    assert deploys[0]["version"] == "v1.0.1-broken"


# ──────────────────────────────────────────────────────────────────
# Test 8: SCN-04 queue depth grows
# ──────────────────────────────────────────────────────────────────

def test_scn04_queue_depth_grows():
    metrics = load_json("SCN-04", "metrics.json")
    baseline_depth = metrics["baseline"]["demo_queue_depth"]
    fault_depth = metrics["fault"]["demo_queue_depth"]
    assert fault_depth > baseline_depth, (
        f"SCN-04: fault queue depth {fault_depth} should be > baseline {baseline_depth}"
    )
    assert fault_depth >= 5, f"SCN-04: fault queue depth {fault_depth} should be >= 5"


# ──────────────────────────────────────────────────────────────────
# Test 9: SCN-05 certificate days drop to 0
# ──────────────────────────────────────────────────────────────────

def test_scn05_certificate_days_drop():
    metrics = load_json("SCN-05", "metrics.json")
    baseline_days = metrics["baseline"]["demo_certificate_days_until_expiry"]
    fault_days = metrics["fault"]["demo_certificate_days_until_expiry"]
    assert baseline_days == 90
    assert fault_days == 0
