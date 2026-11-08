"""
test_deploy_window.py - Unit and Integration Tests for Deploy-Window Evidence (Issue #93).
------------------------------------------------------------------------------------------
Tests deploy lookback windows, dependency service correlation, rollback detection,
relevance classification, and boundary conditions.
"""

from datetime import datetime, timezone
import pytest

from deploy_window import (
    DEFAULT_RECORDED_DEPLOYS,
    build_deploy_url,
    find_deploy_events,
    is_rollback,
    parse_time,
)


def test_is_rollback_detection():
    assert is_rollback("v1.0.0", "v1.0.1", "Routine release") is True  # Downgrade
    assert is_rollback("v1.0.1", "v1.0.0", "Rollback to stable v1.0.0") is True
    assert is_rollback("v1.0.1", "v1.0.0", "Revert broken commit a1b2c3d") is True
    assert is_rollback("v1.0.0-rollback", "v1.0.1", "Hotfix") is True
    assert is_rollback("v1.0.1", "v1.0.0", "Normal feature release") is False


def test_build_deploy_url():
    url = build_deploy_url("https://deploy.example.com", "checkout", "a1b2c3d")
    assert url == "https://deploy.example.com/deploys/checkout/a1b2c3d"
    assert build_deploy_url("", "checkout", "a1b2c3d") == ""
    assert build_deploy_url("https://deploy.example.com", "checkout", "") == ""


def test_find_deploy_events_direct_match():
    deploys = find_deploy_events(
        service="checkout",
        start_time="2026-10-27T08:00:00Z",
        end_time="2026-10-27T08:30:00Z",
    )
    assert len(deploys) == 1
    dep = deploys[0]
    assert dep["service"] == "checkout"
    assert dep["version"] == "v1.0.1-broken"
    assert dep["is_direct"] is True
    assert dep["relevance"] == "direct"


def test_find_deploy_events_lookback_window():
    # Incident started at 08:20:00Z, deploy was at 08:16:00Z (4 minutes prior)
    deploys = find_deploy_events(
        service="checkout",
        incident_start="2026-10-27T08:20:00Z",
        lookback_minutes=60,
    )
    assert len(deploys) == 1
    dep = deploys[0]
    assert dep["version"] == "v1.0.1-broken"
    assert dep["minutes_before_incident"] == 4.0


def test_find_deploy_events_lookback_window_boundary():
    # Deploy at 08:16:00Z.
    # Lookback window starting after deploy (e.g. at 08:30:00 with lookback=10m => window is 08:20 to 08:30)
    deploys_outside = find_deploy_events(
        service="checkout",
        incident_start="2026-10-27T08:30:00Z",
        lookback_minutes=10,
    )
    assert len(deploys_outside) == 0

    # Lookback window containing deploy (e.g. at 08:30:00 with lookback=20m => window is 08:10 to 08:30)
    deploys_inside = find_deploy_events(
        service="checkout",
        incident_start="2026-10-27T08:30:00Z",
        lookback_minutes=20,
    )
    assert len(deploys_inside) == 1


def test_find_deploy_events_dependency_service_correlation():
    custom_deploys = [
        {
            "service": "checkout",
            "version": "v1.0.2",
            "previous_version": "v1.0.1",
            "timestamp": "2026-10-27T08:10:00Z",
            "commit": "c001",
            "author": "ci-bot",
            "status": "completed",
            "message": "Direct checkout release",
        },
        {
            "service": "payments",
            "version": "v2.0.0",
            "previous_version": "v1.9.9",
            "timestamp": "2026-10-27T08:05:00Z",
            "commit": "p001",
            "author": "ci-bot",
            "status": "completed",
            "message": "Payments API upgrade",
        },
        {
            "service": "unrelated",
            "version": "v3.0.0",
            "previous_version": "v2.9.9",
            "timestamp": "2026-10-27T08:00:00Z",
            "commit": "u001",
            "author": "ci-bot",
            "status": "completed",
            "message": "Unrelated service deploy",
        },
    ]

    # Without dependencies: only checkout
    direct_only = find_deploy_events(
        deploys=custom_deploys,
        service="checkout",
        incident_start="2026-10-27T08:15:00Z",
        lookback_minutes=60,
        include_dependencies=False,
    )
    assert len(direct_only) == 1
    assert direct_only[0]["service"] == "checkout"
    assert direct_only[0]["relevance"] == "direct"

    # With dependencies: checkout depends on payments, so payments is included, but unrelated is excluded
    with_deps = find_deploy_events(
        deploys=custom_deploys,
        service="checkout",
        incident_start="2026-10-27T08:15:00Z",
        lookback_minutes=60,
        include_dependencies=True,
    )
    services_found = [d["service"] for d in with_deps]
    assert services_found == ["checkout", "payments"]

    checkout_dep = next(d for d in with_deps if d["service"] == "checkout")
    payments_dep = next(d for d in with_deps if d["service"] == "payments")

    assert checkout_dep["relevance"] == "direct"
    assert checkout_dep["is_direct"] is True
    assert payments_dep["relevance"] == "dependency"
    assert payments_dep["is_direct"] is False


def test_find_deploy_events_timezone_offsets():
    # 08:16:00Z in +05:30 is 13:46:00+05:30
    deploys = find_deploy_events(
        service="checkout",
        start_time="2026-10-27T13:40:00+05:30",
        end_time="2026-10-27T13:50:00+05:30",
    )
    assert len(deploys) == 1
    assert deploys[0]["version"] == "v1.0.1-broken"


def test_find_deploy_events_rejects_reversed_time():
    with pytest.raises(ValueError, match="end_time must be after or equal to start_time"):
        find_deploy_events(
            service="checkout",
            start_time="2026-10-27T09:00:00Z",
            end_time="2026-10-27T08:00:00Z",
        )


def test_find_deploy_events_unknown_service():
    deploys = find_deploy_events(
        service="non-existent-service",
        start_time="2026-10-27T08:00:00Z",
        end_time="2026-10-27T09:00:00Z",
    )
    assert deploys == []
