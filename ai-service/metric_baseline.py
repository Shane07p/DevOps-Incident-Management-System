"""
metric_baseline.py - Metric-Baseline Evidence and PromQL Explorer Links (Issue #93).
-----------------------------------------------------------------------------------
Compares service metrics during an incident against normal baselines (e.g. error rate,
latency, saturation, certificate expiry). Computes anomaly ratios, classifies severity,
and generates Grafana Explore PromQL query links.
"""

from __future__ import annotations

import json
import math
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional
from urllib.parse import urlencode


def parse_time(value: str) -> datetime:
    """Convert an ISO timestamp into a UTC datetime, treating naive as UTC."""
    timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if timestamp.tzinfo is None:
        timestamp = timestamp.replace(tzinfo=timezone.utc)
    return timestamp.astimezone(timezone.utc)


def build_metric_query_url(
    grafana_url: str,
    datasource_uid: str,
    expr: str,
    start_time: str,
    end_time: str,
) -> str:
    """
    Build a Grafana Explore URL for a Prometheus metric query.
    """
    if not grafana_url or not datasource_uid:
        return ""

    explore_state = {
        "datasource": datasource_uid,
        "queries": [
            {
                "refId": "A",
                "datasource": {
                    "uid": datasource_uid,
                    "type": "prometheus",
                },
                "expr": expr,
            }
        ],
        "range": {
            "from": start_time,
            "to": end_time,
        },
    }

    return (
        grafana_url.rstrip("/")
        + "/explore?"
        + urlencode({"left": json.dumps(explore_state)})
    )


def calculate_baseline_ratio(normal_value: float, incident_value: float) -> Optional[float]:
    """
    Calculate the ratio of the incident value compared to the normal baseline.
    Returns None if normal baseline is 0 or negative.
    """
    if normal_value <= 0:
        return None
    return incident_value / normal_value


def format_ratio_summary(
    metric_name: str,
    normal_value: float,
    incident_value: float,
    unit: str = "",
) -> str:
    """
    Format a human-readable comparison description (e.g., 'errors 800.0x normal').
    """
    unit_str = f" {unit}".rstrip() if unit else ""
    ratio = calculate_baseline_ratio(normal_value, incident_value)

    # Inverted metrics where lower is anomalous (e.g., certificate days until expiry)
    if "expiry" in metric_name.lower() or "remaining" in metric_name.lower():
        if normal_value > 0 and incident_value <= 0:
            return f"{metric_name} reached zero{unit_str} (normal baseline {normal_value:g}{unit_str})"
        drop = normal_value - incident_value
        return f"{metric_name} dropped by {drop:g}{unit_str} to {incident_value:g}{unit_str} (baseline {normal_value:g}{unit_str})"

    if ratio is not None:
        if ratio >= 2.0:
            return f"{metric_name} {ratio:.1f}x normal ({incident_value:g}{unit_str} vs baseline {normal_value:g}{unit_str})"
        elif ratio <= 0.5:
            return f"{metric_name} dropped to {ratio:.1f}x normal ({incident_value:g}{unit_str} vs baseline {normal_value:g}{unit_str})"
        else:
            return f"{metric_name} at {incident_value:g}{unit_str} (normal baseline {normal_value:g}{unit_str})"
    else:
        # Baseline is zero
        if incident_value > 0:
            return f"{metric_name} spiked to {incident_value:g}{unit_str} (normal baseline 0{unit_str})"
        return f"{metric_name} at {incident_value:g}{unit_str} (normal baseline 0{unit_str})"


def classify_severity(
    metric_name: str,
    normal_value: float,
    incident_value: float,
    threshold_ratio: float = 1.5,
) -> str:
    """
    Classify anomaly severity (CRITICAL, HIGH, WARNING, NORMAL) based on
    deviation from normal baseline.
    """
    # Inverted metric: certificate expiry
    if "expiry" in metric_name.lower() or "days" in metric_name.lower():
        if incident_value <= 0:
            return "CRITICAL"
        elif incident_value <= 7:
            return "HIGH"
        elif incident_value <= 30:
            return "WARNING"
        return "NORMAL"

    ratio = calculate_baseline_ratio(normal_value, incident_value)
    if ratio is not None:
        if ratio >= 10.0:
            return "CRITICAL"
        elif ratio >= 3.0:
            return "HIGH"
        elif ratio >= threshold_ratio:
            return "WARNING"
        elif ratio <= 0.1:
            return "CRITICAL"
        return "NORMAL"
    else:
        # Zero baseline
        if incident_value >= 10.0:
            return "CRITICAL"
        elif incident_value >= 1.0:
            return "HIGH"
        elif incident_value > 0:
            return "WARNING"
        return "NORMAL"


def evaluate_metric_anomaly(
    metric_name: str,
    normal_value: float,
    incident_value: float,
    unit: str = "",
    threshold_ratio: float = 1.5,
) -> Dict[str, Any]:
    """
    Evaluate statistical anomaly metrics including ratio, delta, percentage change,
    severity, and formatted human-readable observation.
    """
    ratio = calculate_baseline_ratio(normal_value, incident_value)
    delta = incident_value - normal_value
    percent_change = ((delta / normal_value) * 100.0) if normal_value > 0 else (100.0 if incident_value > 0 else 0.0)
    severity = classify_severity(metric_name, normal_value, incident_value, threshold_ratio)
    summary = format_ratio_summary(metric_name, normal_value, incident_value, unit)

    return {
        "metric": metric_name,
        "normal_value": normal_value,
        "incident_value": incident_value,
        "unit": unit,
        "ratio": ratio,
        "delta": delta,
        "percent_change": round(percent_change, 2),
        "severity": severity,
        "summary": summary,
        "is_anomalous": severity in ("CRITICAL", "HIGH", "WARNING"),
    }


# Default recorded metrics matching the five Demo Shop fault scenarios (SCN-01 to SCN-05)
DEFAULT_RECORDED_METRICS: Dict[str, List[Dict[str, Any]]] = {
    "payments": [
        {
            "metric": "demo_http_request_duration_seconds",
            "scenario_id": "SCN-01",
            "observed_at": "2026-10-27T08:15:40Z",
            "observation": "p99 latency 2001.93ms (normal baseline < 50ms, SLA threshold 1500ms)",
            "normal_value": 25.0,
            "incident_value": 2001.93,
            "unit": "ms",
            "severity": "CRITICAL",
        },
        {
            "metric": "demo_certificate_days_until_expiry",
            "scenario_id": "SCN-05",
            "observed_at": "2026-10-30T10:00:00Z",
            "observation": "TLS certificate expiry dropped to 0 days (normal baseline 90 days)",
            "normal_value": 90.0,
            "incident_value": 0.0,
            "unit": "days",
            "severity": "CRITICAL",
        },
        {
            "metric": "demo_ssl_errors_total",
            "scenario_id": "SCN-05",
            "observed_at": "2026-10-30T10:00:05Z",
            "observation": "SSL handshake errors counter incremented to 1 (normal baseline 0)",
            "normal_value": 0.0,
            "incident_value": 1.0,
            "unit": "errors",
            "severity": "HIGH",
        },
    ],
    "checkout": [
        {
            "metric": "demo_http_requests_total_5xx",
            "scenario_id": "SCN-02",
            "observed_at": "2026-10-28T10:00:00Z",
            "observation": "Checkout failure rate spiked to 80% with simulated database deadlocks (normal baseline < 0.1%)",
            "normal_value": 0.001,
            "incident_value": 0.80,
            "unit": "rate",
            "severity": "CRITICAL",
        },
        {
            "metric": "demo_http_requests_total",
            "scenario_id": "SCN-03",
            "observed_at": "2026-10-27T08:16:15Z",
            "observation": "Checkout failed requests spiked after broken deploy (normal baseline < 0.1%)",
            "normal_value": 0.001,
            "incident_value": 0.75,
            "unit": "rate",
            "severity": "CRITICAL",
        },
    ],
    "order-fulfillment": [
        {
            "metric": "demo_queue_depth",
            "scenario_id": "SCN-04",
            "observed_at": "2026-10-29T10:00:00Z",
            "observation": "Order fulfillment queue worker paused, backlog accumulated to 42 orders (normal baseline 0-1)",
            "normal_value": 0.0,
            "incident_value": 42.0,
            "unit": "orders",
            "severity": "HIGH",
        },
        {
            "metric": "demo_queue_lag_seconds",
            "scenario_id": "SCN-04",
            "observed_at": "2026-10-29T10:00:10Z",
            "observation": "Queue processing lag increased to 180s (normal baseline < 5s)",
            "normal_value": 2.0,
            "incident_value": 180.0,
            "unit": "s",
            "severity": "HIGH",
        },
    ],
}


def find_metric_anomalies(
    service: str,
    metric_records: Optional[Dict[str, List[Dict[str, Any]]]] = None,
    metric_name: Optional[str] = None,
    start_time: Optional[str] = None,
    end_time: Optional[str] = None,
    threshold_ratio: float = 1.5,
    query_base_url: str = "",
    datasource_uid: str = "",
) -> List[Dict[str, Any]]:
    """
    Find metric anomalies for a given service by comparing against baseline levels.
    Filters within an optional time range [start_time, end_time].
    Attaches PromQL Grafana Explore URLs when query_base_url and datasource_uid are supplied.
    """
    start_dt = parse_time(start_time) if start_time else None
    end_dt = parse_time(end_time) if end_time else None

    if start_dt and end_dt and end_dt < start_dt:
        raise ValueError("end_time must be after or equal to start_time")

    catalog = metric_records if metric_records is not None else DEFAULT_RECORDED_METRICS
    service_key = service.lower()
    raw_metrics = catalog.get(service_key, [])

    results: List[Dict[str, Any]] = []

    for entry in raw_metrics:
        # Filter by metric name if requested
        if metric_name and entry["metric"].lower() != metric_name.lower():
            continue

        # Filter by time bounds
        if "observed_at" in entry:
            obs_dt = parse_time(entry["observed_at"])
            if start_dt and obs_dt < start_dt:
                continue
            if end_dt and obs_dt > end_dt:
                continue

        normal = float(entry.get("normal_value", 0.0))
        incident = float(entry.get("incident_value", 0.0))
        unit = entry.get("unit", "")
        eval_result = evaluate_metric_anomaly(
            metric_name=entry["metric"],
            normal_value=normal,
            incident_value=incident,
            unit=unit,
            threshold_ratio=threshold_ratio,
        )

        query_url = ""
        if query_base_url and datasource_uid:
            expr = f'{entry["metric"]}{{service="{service}"}}'
            s_from = start_time or entry.get("observed_at", "")
            s_to = end_time or entry.get("observed_at", "")
            query_url = build_metric_query_url(query_base_url, datasource_uid, expr, s_from, s_to)

        anomaly_record = dict(entry)
        anomaly_record.update({
            "service": service,
            "ratio": eval_result["ratio"],
            "delta": eval_result["delta"],
            "percent_change": eval_result["percent_change"],
            "severity": entry.get("severity") or eval_result["severity"],
            "observation": entry.get("observation") or eval_result["summary"],
            "query_url": query_url,
        })
        results.append(anomaly_record)

    return results
