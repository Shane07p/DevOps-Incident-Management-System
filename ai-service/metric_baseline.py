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
from pathlib import Path
from typing import Any, Dict, List, Optional, Set
from urllib.parse import urlencode


# Explicit set of metrics where lower values represent anomalies / degraded health
LOWER_IS_WORSE_METRICS: Set[str] = {
    "demo_certificate_days_until_expiry",
    "certificate_days_until_expiry",
    "days_until_expiry",
}


def is_lower_is_worse_metric(metric_name: str) -> bool:
    """Check whether lower values indicate an anomaly (e.g. certificate expiry)."""
    name = metric_name.lower()
    return name in LOWER_IS_WORSE_METRICS or any(kw in name for kw in ("expiry", "remaining"))


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
    if is_lower_is_worse_metric(metric_name):
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
    unit: str = "",
) -> str:
    """
    Classify anomaly severity (CRITICAL, HIGH, WARNING, NORMAL) based on
    deviation from normal baseline.
    """
    # Inverted metric: certificate expiry
    if is_lower_is_worse_metric(metric_name):
        if incident_value <= 0:
            return "CRITICAL"
        elif incident_value <= 7:
            return "HIGH"
        elif incident_value <= 30:
            return "WARNING"
        return "NORMAL"

    if unit == "rate" or "rate" in metric_name.lower():
        if incident_value >= 0.5:
            return "CRITICAL"
        elif incident_value >= 0.1:
            return "HIGH"
        elif incident_value > 0:
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
    severity = classify_severity(metric_name, normal_value, incident_value, threshold_ratio, unit=unit)
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


def load_recorded_metrics(
    fixtures_dir: Optional[Path] = None,
) -> Dict[str, List[Dict[str, Any]]]:
    """
    Load recorded demo-shop metrics from data/fixtures/scenarios/SCN-0X/.
    Reads normal (baseline) and incident (fault) values from metrics.json,
    and the timestamp from metadata.json.
    Drops static 'observation' and 'severity' fields so they are computed dynamically.
    """
    if fixtures_dir is None:
        fixtures_dir = Path(__file__).resolve().parent / "data" / "fixtures" / "scenarios"

    metrics_by_service: Dict[str, List[Dict[str, Any]]] = {}
    if not fixtures_dir.exists():
        return metrics_by_service

    for scn_dir in sorted(fixtures_dir.glob("SCN-*")):
        meta_file = scn_dir / "metadata.json"
        metrics_file = scn_dir / "metrics.json"
        if not meta_file.exists() or not metrics_file.exists():
            continue

        try:
            metadata = json.loads(meta_file.read_text(encoding="utf-8"))
            metrics_data = json.loads(metrics_file.read_text(encoding="utf-8"))
        except Exception:
            continue

        service = metadata.get("service", "").lower()
        scenario_id = metadata.get("scenario_id", scn_dir.name)
        observed_at = metadata.get("fault_started_at") or metadata.get("recorded_at", "")

        baseline_sec = metrics_data.get("baseline", {})
        fault_sec = metrics_data.get("fault", {})

        service_list = metrics_by_service.setdefault(service, [])

        for metric_name, fault_val in fault_sec.items():
            base_val = baseline_sec.get(metric_name)

            # A dict value holds several fields; "field" names the one read, so the summary
            # says "error_rate" rather than only the counter's name.
            field = ""
            if isinstance(fault_val, dict):
                if "p99" in fault_val:
                    field = "p99"
                    normal_val = float(base_val.get("p99", 0.0)) if isinstance(base_val, dict) else 0.0
                    incident_val = float(fault_val.get("p99", 0.0))
                    unit = fault_val.get("unit", "ms")
                elif "error_rate" in fault_val:
                    field = "error_rate"
                    normal_val = float(base_val.get("error_rate", 0.0)) if isinstance(base_val, dict) else 0.0
                    incident_val = float(fault_val.get("error_rate", 0.0))
                    unit = "rate"
                else:
                    normal_val = 0.0
                    incident_val = 0.0
                    unit = ""
            else:
                normal_val = float(base_val) if base_val is not None else 0.0
                incident_val = float(fault_val)
                if "days" in metric_name:
                    unit = "days"
                elif "depth" in metric_name:
                    unit = "orders"
                elif "lag" in metric_name or "seconds" in metric_name:
                    unit = "s"
                elif "errors" in metric_name:
                    unit = "errors"
                else:
                    unit = ""

            service_list.append({
                "metric": metric_name,
                "field": field,
                "scenario_id": scenario_id,
                "observed_at": observed_at,
                "normal_value": normal_val,
                "incident_value": incident_val,
                "unit": unit,
            })

    return metrics_by_service


DEFAULT_RECORDED_METRICS: Dict[str, List[Dict[str, Any]]] = load_recorded_metrics()


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
        label = f'{entry["metric"]} {entry["field"]}' if entry.get("field") else entry["metric"]
        eval_result = evaluate_metric_anomaly(
            metric_name=label,
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
        anomaly_record.pop("observation", None)
        anomaly_record.pop("severity", None)
        anomaly_record.update({
            "service": service,
            "ratio": eval_result["ratio"],
            "delta": eval_result["delta"],
            "percent_change": eval_result["percent_change"],
            "severity": eval_result["severity"],
            "observation": eval_result["summary"],
            "summary": eval_result["summary"],
            "query_url": query_url,
        })
        results.append(anomaly_record)

    return results
