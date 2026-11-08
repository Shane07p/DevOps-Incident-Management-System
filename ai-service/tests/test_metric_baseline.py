"""
test_metric_baseline.py - Unit and Integration Tests for Metric-Baseline Evidence (Issue #93).
----------------------------------------------------------------------------------------------
Tests metric baseline comparisons, ratio calculations, severity classifications,
PromQL explore query URLs, and scenario anomaly detection across all 5 Demo Shop scenarios.
"""

import json
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import pytest
import yaml

from deploy_window import find_deploy_events
from metric_baseline import (
    DEFAULT_RECORDED_METRICS,
    build_metric_query_url,
    calculate_baseline_ratio,
    classify_severity,
    evaluate_metric_anomaly,
    find_metric_anomalies,
    format_ratio_summary,
    parse_time,
)


def test_calculate_baseline_ratio():
    assert calculate_baseline_ratio(25.0, 200.0) == 8.0
    assert calculate_baseline_ratio(0.001, 0.8) == 800.0
    assert calculate_baseline_ratio(0.0, 5.0) is None
    assert calculate_baseline_ratio(-1.0, 5.0) is None


def test_format_ratio_summary_elevated():
    summary = format_ratio_summary("demo_http_request_duration_seconds", 25.0, 200.0, "ms")
    assert "8.0x normal" in summary
    assert "200 ms vs baseline 25 ms" in summary


def test_format_ratio_summary_zero_baseline():
    summary = format_ratio_summary("demo_ssl_errors_total", 0.0, 2.0, "errors")
    assert "spiked to 2 errors" in summary
    assert "normal baseline 0 errors" in summary


def test_format_ratio_summary_certificate_expiry():
    summary = format_ratio_summary("demo_certificate_days_until_expiry", 90.0, 0.0, "days")
    assert "reached zero" in summary
    assert "normal baseline 90 days" in summary


def test_classify_severity_thresholds():
    assert classify_severity("http_requests", 10.0, 150.0) == "CRITICAL"  # 15x
    assert classify_severity("http_requests", 10.0, 40.0) == "HIGH"       # 4x
    assert classify_severity("http_requests", 10.0, 18.0) == "WARNING"    # 1.8x
    assert classify_severity("http_requests", 10.0, 12.0) == "NORMAL"     # 1.2x


def test_classify_severity_certificate_expiry():
    assert classify_severity("demo_certificate_days_until_expiry", 90.0, 0.0) == "CRITICAL"
    assert classify_severity("demo_certificate_days_until_expiry", 90.0, 5.0) == "HIGH"
    assert classify_severity("demo_certificate_days_until_expiry", 90.0, 20.0) == "WARNING"
    assert classify_severity("demo_certificate_days_until_expiry", 90.0, 80.0) == "NORMAL"


def test_evaluate_metric_anomaly_structure():
    eval_res = evaluate_metric_anomaly("latency", 25.0, 2001.93, unit="ms")
    assert eval_res["metric"] == "latency"
    assert eval_res["normal_value"] == 25.0
    assert eval_res["incident_value"] == 2001.93
    assert eval_res["ratio"] == pytest.approx(80.0772, rel=1e-3)
    assert eval_res["delta"] == pytest.approx(1976.93, rel=1e-3)
    assert eval_res["severity"] == "CRITICAL"
    assert eval_res["is_anomalous"] is True


def test_build_metric_query_url_promql():
    url = build_metric_query_url(
        grafana_url="http://grafana.example.com:3000",
        datasource_uid="prom-prod-01",
        expr='demo_http_request_duration_seconds{service="payments"}',
        start_time="2026-10-27T08:00:00Z",
        end_time="2026-10-27T08:30:00Z",
    )
    parsed = urlparse(url)
    assert parsed.netloc == "grafana.example.com:3000"
    assert parsed.path == "/explore"

    qs = parse_qs(parsed.query)
    left_state = json.loads(qs["left"][0])
    assert left_state["datasource"] == "prom-prod-01"
    assert left_state["queries"][0]["datasource"]["type"] == "prometheus"
    assert left_state["queries"][0]["expr"] == 'demo_http_request_duration_seconds{service="payments"}'
    assert left_state["range"]["from"] == "2026-10-27T08:00:00Z"
    assert left_state["range"]["to"] == "2026-10-27T08:30:00Z"


def test_build_metric_query_url_empty_inputs():
    assert build_metric_query_url("", "prom-01", "expr", "start", "end") == ""
    assert build_metric_query_url("http://localhost:3000", "", "expr", "start", "end") == ""


def test_find_metric_anomalies_all_five_scenarios():
    # SCN-01: payments latency
    scn1 = find_metric_anomalies("payments", start_time="2026-10-27T08:00:00Z", end_time="2026-10-27T08:30:00Z")
    assert len(scn1) == 1
    assert scn1[0]["metric"] == "demo_http_request_duration_seconds"
    assert scn1[0]["scenario_id"] == "SCN-01"
    assert scn1[0]["severity"] == "CRITICAL"

    # SCN-02: checkout 5xx error spike
    scn2 = find_metric_anomalies("checkout", metric_name="demo_http_requests_total_5xx")
    assert len(scn2) == 1
    assert scn2[0]["scenario_id"] == "SCN-02"
    assert scn2[0]["incident_value"] == 0.80

    # SCN-03: checkout broken deploy failures
    scn3 = find_metric_anomalies("checkout", metric_name="demo_http_requests_total")
    assert len(scn3) == 1
    assert scn3[0]["scenario_id"] == "SCN-03"

    # SCN-04: order-fulfillment queue backlog
    scn4 = find_metric_anomalies("order-fulfillment")
    assert len(scn4) >= 1
    depth_metric = next(m for m in scn4 if m["metric"] == "demo_queue_depth")
    assert depth_metric["incident_value"] == 42.0
    assert depth_metric["scenario_id"] == "SCN-04"

    # SCN-05: payments expiring certificate and SSL errors
    scn5 = find_metric_anomalies("payments", start_time="2026-10-30T09:50:00Z", end_time="2026-10-30T10:10:00Z")
    metrics_5 = {m["metric"] for m in scn5}
    assert "demo_certificate_days_until_expiry" in metrics_5
    assert "demo_ssl_errors_total" in metrics_5


def test_find_metric_anomalies_with_grafana_url():
    anomalies = find_metric_anomalies(
        service="payments",
        metric_name="demo_http_request_duration_seconds",
        query_base_url="http://localhost:3000",
        datasource_uid="prom-uid",
    )
    assert len(anomalies) == 1
    assert "/explore?" in anomalies[0]["query_url"]
    assert "prom-uid" in anomalies[0]["query_url"]


def test_find_metric_anomalies_time_window_filtering():
    inside = find_metric_anomalies("payments", start_time="2026-10-27T08:00:00Z", end_time="2026-10-27T08:30:00Z")
    assert len(inside) == 1

    outside = find_metric_anomalies("payments", start_time="2026-10-27T09:00:00Z", end_time="2026-10-27T09:30:00Z")
    assert len(outside) == 0


def test_find_metric_anomalies_rejects_reversed_time_window():
    with pytest.raises(ValueError, match="end_time must be after or equal to start_time"):
        find_metric_anomalies(
            "payments",
            start_time="2026-10-27T09:00:00Z",
            end_time="2026-10-27T08:00:00Z",
        )


def test_find_metric_anomalies_unknown_service():
    results = find_metric_anomalies("unknown-service")
    assert results == []


def test_every_scenario_finds_expected_metric_and_deploy_evidence():
    """Validates that for all #64 scenarios, metric and deploy evidence matches expected signals."""
    scenarios_file = Path(__file__).resolve().parents[1] / "data" / "scenarios.yaml"
    with scenarios_file.open(encoding="utf-8") as f:
        data = yaml.safe_load(f)

    for scn in data["scenarios"]:
        service = scn["service"]
        expected_signals = scn["expected_signals"]

        # 1. Metric evidence
        anomalies = find_metric_anomalies(service=service)
        assert len(anomalies) > 0, f"Expected metric anomalies for {scn['scenario_id']} on {service}"

        expected_metric_text = expected_signals["metric"]
        matching_anomaly = any(
            anomaly["metric"] in expected_metric_text
            or any(token in expected_metric_text for token in [anomaly["metric"], anomaly["metric"].rsplit("_", 1)[0]])
            for anomaly in anomalies
        )
        assert matching_anomaly, (
            f"Scenario {scn['scenario_id']} expected metric '{expected_metric_text}', "
            f"found {[a['metric'] for a in anomalies]}"
        )

        # 2. Deploy event evidence
        expected_deploy = expected_signals.get("deploy_event")
        if expected_deploy:
            deploys = find_deploy_events(service=service)
            assert any(d["version"] == expected_deploy for d in deploys), (
                f"Scenario {scn['scenario_id']} expected deploy '{expected_deploy}'"
            )
