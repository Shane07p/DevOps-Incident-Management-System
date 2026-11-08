"""
deploy_window.py - Deploy Window Evidence and Dependency Tracking (Issue #93).
------------------------------------------------------------------------------
Correlates incidents with recent deployments to the target service and its upstream
dependencies within a configurable lookback window (default: 2 hours / 120 minutes).
Detects rollbacks, regressions, and dependency deployments.
"""

from __future__ import annotations

import re
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional, Set


def parse_time(value: str) -> datetime:
    """Convert an ISO timestamp into a UTC datetime, treating naive as UTC."""
    timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if timestamp.tzinfo is None:
        timestamp = timestamp.replace(tzinfo=timezone.utc)
    return timestamp.astimezone(timezone.utc)


DEFAULT_SERVICE_DEPENDENCIES: Dict[str, List[str]] = {
    "payments": ["bank_gateway"],
    "checkout": ["payments", "search", "order-fulfillment"],
    "search": [],
    "order-fulfillment": [],
}

DEFAULT_RECORDED_DEPLOYS: List[Dict[str, Any]] = [
    {
        "service": "checkout",
        "version": "v1.0.1-broken",
        "previous_version": "v1.0.0",
        "timestamp": "2026-10-27T08:16:00Z",
        "commit": "a1b2c3d",
        "author": "ci-bot",
        "status": "completed",
        "message": "Deployed v1.0.1-broken to checkout service causing immediate checkout deadlock",
    },
    {
        "service": "payments",
        "version": "v1.0.0",
        "previous_version": "v0.9.9",
        "timestamp": "2026-10-26T12:00:00Z",
        "commit": "f4708c4",
        "author": "ci-bot",
        "status": "completed",
        "message": "Routine stable deployment to payments service",
    },
]


def is_rollback(version: str, previous_version: str = "", message: str = "") -> bool:
    """
    Detect whether a deployment represents a rollback or revert.
    Checks commit message, version name markers, or version decrement.
    """
    msg_lower = (message or "").lower()
    ver_lower = (version or "").lower()

    if any(keyword in msg_lower for keyword in ("rollback", "roll back", "revert", "rolled back")):
        return True
    if any(keyword in ver_lower for keyword in ("rollback", "revert")):
        return True

    # Try simple semantic version comparison (v1.0.0 < v1.0.1)
    v_match = re.search(r"v?(\d+)\.(\d+)(?:\.(\d+))?", ver_lower)
    prev_match = re.search(r"v?(\d+)\.(\d+)(?:\.(\d+))?", (previous_version or "").lower())
    if v_match and prev_match:
        try:
            curr_parts = [int(p) if p is not None else 0 for p in v_match.groups()]
            prev_parts = [int(p) if p is not None else 0 for p in prev_match.groups()]
            if curr_parts < prev_parts:
                return True
        except (ValueError, TypeError):
            pass

    return False


def build_deploy_url(base_url: str, service: str, commit: str) -> str:
    """
    Construct a link to the commit or deployment dashboard.
    """
    if not base_url or not commit:
        return ""
    return f"{base_url.rstrip('/')}/deploys/{service}/{commit}"


def find_deploy_events(
    deploys: Optional[List[Dict[str, Any]]] = None,
    service: str = "",
    incident_start: Optional[str] = None,
    incident_end: Optional[str] = None,
    start_time: Optional[str] = None,
    end_time: Optional[str] = None,
    lookback_minutes: int = 120,
    service_dependencies: Optional[Dict[str, List[str]]] = None,
    include_dependencies: bool = False,
    deploy_base_url: str = "",
) -> List[Dict[str, Any]]:
    """
    Query deployments for a target service and optionally its upstream dependencies
    within a lookback window preceding an incident or within explicit [start_time, end_time].
    """
    dataset = deploys if deploys is not None else DEFAULT_RECORDED_DEPLOYS
    target_service = service.lower()

    # Determine time window
    start_dt: Optional[datetime] = None
    end_dt: Optional[datetime] = None
    inc_start_dt: Optional[datetime] = None

    if start_time or end_time:
        start_dt = parse_time(start_time) if start_time else None
        end_dt = parse_time(end_time) if end_time else None
        if start_dt and end_dt and end_dt < start_dt:
            raise ValueError("end_time must be after or equal to start_time")
    elif incident_start:
        inc_start_dt = parse_time(incident_start)
        start_dt = inc_start_dt - timedelta(minutes=lookback_minutes)
        if incident_end:
            end_dt = parse_time(incident_end)
        else:
            # Default to incident start plus a 10-minute grace window for inflight deploys
            end_dt = inc_start_dt + timedelta(minutes=10)

    # Determine relevant services
    relevant_services: Set[str] = {target_service} if target_service else set()
    dependencies_map = service_dependencies if service_dependencies is not None else DEFAULT_SERVICE_DEPENDENCIES

    if include_dependencies and target_service in dependencies_map:
        for dep in dependencies_map[target_service]:
            relevant_services.add(dep.lower())

    matching_deploys: List[Dict[str, Any]] = []

    for dep in dataset:
        dep_service = dep.get("service", "").lower()
        if relevant_services and dep_service not in relevant_services:
            continue

        timestamp_str = dep.get("timestamp")
        if not timestamp_str:
            continue

        try:
            dep_dt = parse_time(timestamp_str)
        except Exception:
            continue

        if start_dt and dep_dt < start_dt:
            continue
        if end_dt and dep_dt > end_dt:
            continue

        is_dir = (dep_service == target_service)
        is_rb = is_rollback(
            version=dep.get("version", ""),
            previous_version=dep.get("previous_version", ""),
            message=dep.get("message", ""),
        )

        record = dict(dep)
        record["is_direct"] = is_dir
        record["is_rollback"] = is_rb
        record["relevance"] = "direct" if is_dir else "dependency"

        if inc_start_dt:
            diff_minutes = (inc_start_dt - dep_dt).total_seconds() / 60.0
            record["minutes_before_incident"] = round(diff_minutes, 1)

        if deploy_base_url:
            record["deploy_url"] = build_deploy_url(deploy_base_url, dep.get("service", ""), dep.get("commit", ""))

        matching_deploys.append(record)

    # Sort descending by timestamp (most recent first)
    matching_deploys.sort(
        key=lambda item: parse_time(item["timestamp"]),
        reverse=True,
    )

    return matching_deploys
