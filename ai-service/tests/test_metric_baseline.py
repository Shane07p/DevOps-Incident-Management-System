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
    assert scn1[0]["normal_value"] == 8.5
    assert scn1[0]["incident_value"] == 2001.93
    assert scn1[0]["severity"] == "CRITICAL"

    # SCN-02: checkout 5xx error spike (rising from 0 to 0.82)
    scn2 = find_metric_anomalies("checkout", metric_name="demo_http_requests_total")
    scn2_match = [m for m in scn2 if m["scenario_id"] == "SCN-02"]
    assert len(scn2_match) == 1
    assert scn2_match[0]["normal_value"] == 0.0
    assert scn2_match[0]["incident_value"] == 0.82
    assert scn2_match[0]["severity"] == "CRITICAL"

    # SCN-03: checkout broken deploy failures (rising from 0 to 1.0)
    scn3_match = [m for m in scn2 if m["scenario_id"] == "SCN-03"]
    assert len(scn3_match) == 1
    assert scn3_match[0]["normal_value"] == 0.0
    assert scn3_match[0]["incident_value"] == 1.0
    assert scn3_match[0]["severity"] == "CRITICAL"

    # SCN-04: order-fulfillment queue backlog (depth rises to 8, lag to 7)
    scn4 = find_metric_anomalies("order-fulfillment")
    assert len(scn4) >= 1
    depth_metric = next(m for m in scn4 if m["metric"] == "demo_queue_depth")
    assert depth_metric["normal_value"] == 1.0
    assert depth_metric["incident_value"] == 8.0
    assert depth_metric["scenario_id"] == "SCN-04"

    # SCN-05: payments expiring certificate and SSL errors
    scn5 = find_metric_anomalies("payments", start_time="2026-11-08T15:30:00Z", end_time="2026-11-08T15:35:00Z")
    cert_metric = next(m for m in scn5 if m["metric"] == "demo_certificate_days_until_expiry")
    ssl_metric = next(m for m in scn5 if m["metric"] == "demo_ssl_errors_total")
    assert cert_metric["normal_value"] == 90.0
    assert cert_metric["incident_value"] == 0.0
    assert cert_metric["severity"] == "CRITICAL"
    assert ssl_metric["normal_value"] == 0.0
    assert ssl_metric["incident_value"] == 15.0
    assert ssl_metric["severity"] == "CRITICAL"


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
    """
    Validates that for all five scenarios, metric and deploy evidence assert the real recorded numbers:
    - SCN-01: payments latency p99 rises from 8.5 to 2001.93 ms (CRITICAL)
    - SCN-02: checkout error rate rises from 0 to 0.82 (CRITICAL)
    - SCN-03: checkout error rate rises from 0 to 1.0, and shows v1.0.1-broken as a direct deploy minutes before fault
    - SCN-04: order-fulfillment queue depth rises from 1 to 8, lag rises from 0 to 7s
    - SCN-05: payments certificate days drop from 90 to 0, and SSL errors rise from 0 to 15
    """
    # SCN-01: payments latency
    anomalies_01 = find_metric_anomalies("payments", start_time="2026-10-27T08:00:00Z", end_time="2026-10-27T08:30:00Z")
    lat = next(m for m in anomalies_01 if m["metric"] == "demo_http_request_duration_seconds" and m["scenario_id"] == "SCN-01")
    assert lat["normal_value"] == 8.5
    assert lat["incident_value"] == 2001.93
    assert lat["severity"] == "CRITICAL"
    assert "2001.93" in lat["observation"]

    # SCN-02: checkout error rate rising from 0 to 0.82
    anomalies_02 = find_metric_anomalies("checkout", start_time="2026-11-08T15:20:00Z", end_time="2026-11-08T15:23:00Z")
    err_02 = next(m for m in anomalies_02 if m["scenario_id"] == "SCN-02")
    assert err_02["metric"] == "demo_http_requests_total"
    assert err_02["normal_value"] == 0.0
    assert err_02["incident_value"] == 0.82
    assert err_02["severity"] == "CRITICAL"
    assert "0.82" in err_02["observation"]

    # SCN-03: checkout error rate rising from 0 to 1.0 and direct deploy v1.0.1-broken minutes before fault
    anomalies_03 = find_metric_anomalies("checkout", start_time="2026-11-08T15:22:45Z", end_time="2026-11-08T15:23:00Z")
    err_03 = next(m for m in anomalies_03 if m["scenario_id"] == "SCN-03")
    assert err_03["metric"] == "demo_http_requests_total"
    assert err_03["normal_value"] == 0.0
    assert err_03["incident_value"] == 1.0
    deploys_03 = find_deploy_events(service="checkout", incident_start="2026-11-08T15:25:00Z")
    assert len(deploys_03) >= 1
    assert deploys_03[0]["version"] == "v1.0.1-broken"
    assert deploys_03[0]["is_direct"] is True
    assert deploys_03[0]["minutes_before_incident"] < 5.0

    # SCN-04: order-fulfillment queue depth rises from 1 to 8, lag from 0 to 7
    anomalies_04 = find_metric_anomalies("order-fulfillment", start_time="2026-11-08T15:30:00Z", end_time="2026-11-08T15:35:00Z")
    depth = next(m for m in anomalies_04 if m["metric"] == "demo_queue_depth")
    lag = next(m for m in anomalies_04 if m["metric"] == "demo_queue_lag_seconds")
    assert depth["normal_value"] == 1.0
    assert depth["incident_value"] == 8.0
    assert depth["severity"] == "HIGH"
    assert lag["normal_value"] == 0.0
    assert lag["incident_value"] == 7.0
    assert lag["severity"] == "HIGH"

    # SCN-05: payments cert days drop from 90 to 0, SSL errors rise from 0 to 15
    anomalies_05 = find_metric_anomalies("payments", start_time="2026-11-08T15:30:00Z", end_time="2026-11-08T15:35:00Z")
    cert = next(m for m in anomalies_05 if m["metric"] == "demo_certificate_days_until_expiry")
    ssl = next(m for m in anomalies_05 if m["metric"] == "demo_ssl_errors_total")
    assert cert["normal_value"] == 90.0
    assert cert["incident_value"] == 0.0
    assert cert["severity"] == "CRITICAL"
    assert ssl["normal_value"] == 0.0
    assert ssl["incident_value"] == 15.0
    assert ssl["severity"] == "CRITICAL"


def test_lower_is_worse_metric_consistency():
    from metric_baseline import LOWER_IS_WORSE_METRICS
    # Both format_ratio_summary and classify_severity should treat lower-is-worse identically
    metric = "demo_certificate_days_until_expiry"
    assert metric in LOWER_IS_WORSE_METRICS
    summary = format_ratio_summary(metric, 90.0, 0.0, "days")
    severity = classify_severity(metric, 90.0, 0.0)
    assert "reached zero" in summary
    assert severity == "CRITICAL"

    # A metric in LOWER_IS_WORSE_METRICS:
    summary_days = format_ratio_summary("certificate_days_until_expiry", 30.0, 5.0, "days")
    severity_days = classify_severity("certificate_days_until_expiry", 30.0, 5.0)
    assert "dropped by 25" in summary_days
    assert severity_days == "HIGH"


def test_find_metric_anomalies_computes_summary_and_severity():
    results = find_metric_anomalies("payments", metric_name="demo_http_request_duration_seconds")
    assert len(results) >= 1
    item = results[0]
    # Summary shows computed ratio, e.g. "235.5x normal"
    assert "x normal" in item["observation"]
    assert "x normal" in item["summary"]
    assert item["severity"] == "CRITICAL"
