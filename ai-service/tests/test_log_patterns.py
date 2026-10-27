
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import pytest

from log_patterns import (
    build_query_url,
    find_log_patterns,
    load_recorded_logs,
    parse_time,
)


def make_log(timestamp, event, service="payments"):
    return {
        "timestamp": timestamp,
        "service": service,
        "event": event,
    }


START = "2026-10-27T10:00:00Z"
END = "2026-10-27T11:00:00Z"


def detect(logs, service="payments"):
    return find_log_patterns(
        logs,
        service=service,
        incident_start=START,
        incident_end=END,
    )


def test_detects_new_pattern():
    logs = [
        make_log("2026-10-27T09:30:00Z", "payment.authorized"),
        make_log("2026-10-27T10:05:00Z", "payment.gateway.slow"),
        make_log("2026-10-27T10:10:00Z", "payment.gateway.slow"),
    ]

    results = detect(logs)

    assert len(results) == 1
    assert results[0]["pattern"] == "payment.gateway.slow"
    assert results[0]["count_before"] == 0
    assert results[0]["count_after"] == 2
    assert results[0]["status"] == "new"


def test_detects_spiking_pattern():
    logs = [
        make_log("2026-10-27T09:30:00Z", "queue.backlog_warning"),
        make_log("2026-10-27T10:05:00Z", "queue.backlog_warning"),
        make_log("2026-10-27T10:10:00Z", "queue.backlog_warning"),
        make_log("2026-10-27T10:15:00Z", "queue.backlog_warning"),
    ]

    results = detect(logs)

    assert len(results) == 1
    assert results[0]["count_before"] == 1
    assert results[0]["count_after"] == 3
    assert results[0]["status"] == "spiking"


def test_ignores_other_services():
    logs = [
        make_log(
            "2026-10-27T10:05:00Z",
            "search.executed",
            service="search",
        )
    ]

    assert detect(logs) == []


def test_rejects_invalid_time_window():
    with pytest.raises(ValueError):
        find_log_patterns(
            [],
            service="payments",
            incident_start=END,
            incident_end=START,
        )


def test_demo_shop_payment_slowdown():
    logs = [
        make_log("2026-10-27T09:10:00Z", "payment.authorized"),
        make_log("2026-10-27T09:20:00Z", "payment.authorized"),
        make_log("2026-10-27T10:05:00Z", "payment.gateway.slow"),
        make_log("2026-10-27T10:10:00Z", "payment.gateway.slow"),
        make_log("2026-10-27T10:15:00Z", "payment.gateway.slow"),
    ]

    results = detect(logs)

    assert len(results) == 1
    assert results[0]["pattern"] == "payment.gateway.slow"
    assert results[0]["count_before"] == 0
    assert results[0]["count_after"] == 3
    assert results[0]["status"] == "new"


def test_recorded_demo_shop_slow_gateway():
    fixture = (
        Path(__file__).parent
        / "fixtures"
        / "demo_shop_slow_gateway.jsonl"
    )

    logs = load_recorded_logs(fixture)

    activation = next(
        log for log in logs
        if log["event"] == "fault.activated"
    )

    incident_start = activation["timestamp"]

    start = datetime.fromisoformat(
        incident_start.replace("Z", "+00:00")
    )

    incident_end = (
        start + timedelta(seconds=20)
    ).isoformat()

    results = find_log_patterns(
        logs,
        service="payments",
        incident_start=incident_start,
        incident_end=incident_end,
    )

    slow_gateway = next(
        result for result in results
        if result["pattern"] == "payment.gateway.slow"
    )

    assert slow_gateway["status"] == "new"
    assert slow_gateway["count_before"] == 0
    assert slow_gateway["count_after"] == 5


def test_grafana_query_link():
    url = build_query_url(
        "http://localhost:3000",
        "loki",
        "payments",
        "payment.gateway.slow",
        "2026-10-27T08:16:07Z",
        "2026-10-27T08:16:27Z",
    )

    parsed = urlparse(url)
    state = json.loads(parse_qs(parsed.query)["left"][0])

    assert parsed.path == "/explore"
    assert state["datasource"] == "loki"
    assert state["queries"][0]["expr"] == (
        '{service="payments"} | json | event="payment.gateway.slow"'
    )


def test_timestamp_without_timezone_is_utc():
    without_timezone = parse_time("2026-10-27T08:16:07")
    with_timezone = parse_time("2026-10-27T08:16:07Z")

    assert without_timezone == with_timezone
    assert without_timezone.tzinfo == timezone.utc
