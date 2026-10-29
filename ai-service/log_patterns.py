
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode


def parse_time(value):
    """Convert an ISO timestamp into a UTC datetime."""
    timestamp = datetime.fromisoformat(
        value.replace("Z", "+00:00")
    )

    if timestamp.tzinfo is None:
        timestamp = timestamp.replace(tzinfo=timezone.utc)

    return timestamp.astimezone(timezone.utc)


def build_query_url(
    grafana_url,
    datasource_uid,
    service,
    pattern,
    incident_start,
    incident_end,
):
    """Build a Grafana Explore URL for the matching Loki logs."""
    if not grafana_url or not datasource_uid:
        return ""

    safe_service = json.dumps(service)
    safe_pattern = json.dumps(pattern)

    expression = (
        f'{{service={safe_service}}} '
        f'| json | event={safe_pattern}'
    )

    explore_state = {
        "datasource": datasource_uid,
        "queries": [
            {
                "refId": "A",
                "datasource": {
                    "uid": datasource_uid,
                    "type": "loki",
                },
                "expr": expression,
            }
        ],
        "range": {
            "from": incident_start,
            "to": incident_end,
        },
    }

    return (
        grafana_url.rstrip("/")
        + "/explore?"
        + urlencode({"left": json.dumps(explore_state)})
    )


def find_log_patterns(
    logs,
    service,
    incident_start,
    incident_end,
    query_base_url="",
    datasource_uid="",
):
    """
    Compare incident logs with an equally long period
    immediately before the incident.
    """
    start = parse_time(incident_start)
    end = parse_time(incident_end)

    if end <= start:
        raise ValueError(
            "incident_end must be after incident_start"
        )

    before_start = start - (end - start)

    before = Counter()
    after = Counter()

    for log in logs:
        if log.get("service") != service:
            continue

        timestamp = parse_time(log["timestamp"])
        pattern = log.get("event")

        if not pattern:
            continue

        if before_start <= timestamp < start:
            before[pattern] += 1
        elif start <= timestamp < end:
            after[pattern] += 1

    results = []

    for pattern, after_count in after.items():
        before_count = before[pattern]

        is_new = before_count == 0
        is_spiking = (
            before_count > 0
            and after_count >= 3
            and after_count >= before_count * 2
        )

        if not (is_new or is_spiking):
            continue

        query_url = build_query_url(
            query_base_url,
            datasource_uid,
            service,
            pattern,
            incident_start,
            incident_end,
        )

        results.append({
            "pattern": pattern,
            "count_before": before_count,
            "count_after": after_count,
            "status": "new" if is_new else "spiking",
            "query_url": query_url,
        })

    return sorted(
        results,
        key=lambda item: (
            item["count_after"] - item["count_before"]
        ),
        reverse=True,
    )


def load_recorded_logs(path):
    """Read recorded demo-shop JSONL logs."""
    with Path(path).open(encoding="utf-8") as file:
        return [
            json.loads(line)
            for line in file
            if line.strip()
        ]
