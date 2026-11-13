"""The scenario recorder and the demo shop's control token (Codex on #262).

A refused control call must stop the recording: saving logs from a run whose fault never switched on would
produce a fixture that claims a fault it doesn't show.
"""

from __future__ import annotations

import httpx
import pytest

import record_scenario


def demo_shop(expected_token: str | None, seen: list[httpx.Request]) -> httpx.MockTransport:
    """A stand-in demo shop: control routes need the token when one is expected; logs come back empty."""

    def handle(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        if expected_token and request.headers.get("Authorization") != f"Bearer {expected_token}":
            return httpx.Response(401, json={"detail": "A valid control token is required"})
        if request.url.path == "/faults/deploy-event":
            # The real shop answers JSON null when no deploy has happened.
            return httpx.Response(200, content=b"null", headers={"Content-Type": "application/json"})
        if request.url.path == "/telemetry/logs":
            return httpx.Response(200, json=[])
        return httpx.Response(200, json={"status": "ok"})

    return httpx.MockTransport(handle)


@pytest.fixture
def saved(monkeypatch):
    calls: list[dict] = []
    monkeypatch.setattr(record_scenario, "save_recording", lambda **kwargs: calls.append(kwargs) or kwargs)
    monkeypatch.setattr(record_scenario.time, "sleep", lambda seconds: None)
    return calls


def test_the_control_token_from_the_environment_goes_on_every_control_call(monkeypatch, saved):
    monkeypatch.setenv("DEMO_SHOP_CONTROL_TOKEN", "secret-control-token")
    seen: list[httpx.Request] = []
    client = httpx.Client(base_url="http://shop.test", transport=demo_shop("secret-control-token", seen),
                          headers=record_scenario.control_headers())

    record_scenario.record_live("SCN-01", baseline_seconds=0, fault_seconds=0, base_url="http://shop.test", client=client)

    assert len(saved) == 1
    assert {request.url.path for request in seen} >= {"/faults/reset", "/telemetry/logs/clear", "/traffic/start",
                                                      "/faults/deploy-event", "/traffic/stop", "/telemetry/logs"}
    assert all(request.headers["Authorization"] == "Bearer secret-control-token" for request in seen)


def test_a_refused_control_call_stops_the_recording_and_saves_nothing(monkeypatch, saved):
    monkeypatch.delenv("DEMO_SHOP_CONTROL_TOKEN", raising=False)
    seen: list[httpx.Request] = []
    client = httpx.Client(base_url="http://shop.test", transport=demo_shop("secret-control-token", seen),
                          headers=record_scenario.control_headers())

    with pytest.raises(RuntimeError, match="DEMO_SHOP_CONTROL_TOKEN"):
        record_scenario.record_live("SCN-01", baseline_seconds=0, fault_seconds=0, base_url="http://shop.test",
                                    client=client)

    assert saved == []
    assert [request.url.path for request in seen] == ["/faults/reset"]


def test_without_a_token_no_authorization_header_is_sent(monkeypatch):
    monkeypatch.delenv("DEMO_SHOP_CONTROL_TOKEN", raising=False)

    assert record_scenario.control_headers() == {}
