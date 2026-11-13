"""Control routes require the configured token; public shop probes stay open."""

from pathlib import Path
import runpy
from unittest.mock import AsyncMock, Mock

import pytest
from fastapi.testclient import TestClient

from demo_shop.app import app
from demo_shop.config import Config
from demo_shop.faults import fault_manager
from demo_shop.queue import order_queue
from demo_shop.traffic import traffic_generator


CONTROL_TOKEN = "test-control-token"
CONTROL_ROUTES = [
    ("POST", "/faults/reset", None),
    ("POST", "/faults/slow_gateway", {"enabled": True}),
    ("POST", "/traffic/start", {
        "rate": 1.0, "duration_seconds": 1, "target_url": "http://localhost:8001",
    }),
    ("POST", "/traffic/stop", None),
    ("GET", "/faults", None),
    ("GET", "/faults/deploy-event", None),
    ("GET", "/traffic/status", None),
    ("GET", "/telemetry/logs", None),
    ("POST", "/telemetry/logs/clear", None),
]
SHOP_ROUTES = [
    ("POST", "/payments/authorize", {
        "order_id": "ord-open-probe", "amount": 10.0, "card_token": "tok_visa",
    }),
    ("POST", "/checkout", {
        "customer_email": "probe@example.com",
        "items": [{"product_id": "prod-101", "quantity": 1, "unit_price": 10.0}],
    }),
    ("GET", "/search", None),
    ("GET", "/products", None),
    ("GET", "/health", None),
    ("GET", "/ready", None),
]


@pytest.fixture
def configured_token(monkeypatch):
    monkeypatch.setattr(Config, "CONTROL_TOKEN", CONTROL_TOKEN)


@pytest.mark.parametrize("token", [None, "server-control-token"])
def test_control_token_reads_environment(monkeypatch, token):
    if token is None:
        monkeypatch.delenv("DEMO_SHOP_CONTROL_TOKEN", raising=False)
    else:
        monkeypatch.setenv("DEMO_SHOP_CONTROL_TOKEN", token)
    server_config = runpy.run_path(
        str(Path(__file__).parents[1] / "demo_shop" / "config.py"),
    )["Config"]
    assert server_config.CONTROL_TOKEN == (token or "")


@pytest.mark.parametrize("method,path,payload", CONTROL_ROUTES)
@pytest.mark.parametrize("authorization", [None, "Bearer wrong-token"])
def test_control_routes_reject_missing_or_wrong_token(
    client, configured_token, method, path, payload, authorization,
):
    headers = {} if authorization is None else {"Authorization": authorization}
    response = client.request(method, path, json=payload, headers=headers)

    assert response.status_code == 401
    assert response.json() == {"detail": "A valid control token is required"}


@pytest.mark.parametrize("method,path,payload", CONTROL_ROUTES)
def test_control_routes_accept_matching_token(
    client, configured_token, monkeypatch, method, path, payload,
):
    # The real start/stop handlers run, but no traffic is sent outside the test app.
    monkeypatch.setattr(traffic_generator, "_loop", AsyncMock())
    headers = {"Authorization": f"Bearer {CONTROL_TOKEN}"}
    try:
        response = client.request(method, path, json=payload, headers=headers)
        assert response.status_code == 200
        if path == "/faults/slow_gateway":
            assert fault_manager.is_enabled("slow_gateway") is True
        elif path == "/traffic/start":
            assert traffic_generator.is_running is True
    finally:
        client.post("/traffic/stop", headers=headers)


@pytest.mark.parametrize("authorization", [None, "Bearer wrong-token"])
def test_rejected_mutations_leave_control_state_unchanged(
    client, configured_token, monkeypatch, authorization,
):
    headers = {} if authorization is None else {"Authorization": authorization}
    start = Mock()
    stop = AsyncMock()
    monkeypatch.setattr(traffic_generator, "start", start)
    monkeypatch.setattr(traffic_generator, "stop", stop)
    fault_manager.enable("slow_gateway")

    assert client.post("/faults/reset", headers=headers).status_code == 401
    assert fault_manager.is_enabled("slow_gateway") is True
    assert client.post(
        "/faults/slow_gateway", json={"enabled": False}, headers=headers,
    ).status_code == 401
    assert fault_manager.is_enabled("slow_gateway") is True
    assert client.post("/traffic/start", headers=headers, json={}).status_code == 401
    assert client.post("/traffic/stop", headers=headers).status_code == 401
    start.assert_not_called()
    stop.assert_not_awaited()


@pytest.mark.parametrize("method,path,payload", SHOP_ROUTES)
def test_shop_routes_stay_open_with_control_token_configured(
    client, configured_token, method, path, payload,
):
    assert client.request(method, path, json=payload).status_code == 200


@pytest.mark.parametrize("method,path,payload", CONTROL_ROUTES)
def test_empty_token_allows_control_routes_outside_production(
    client, monkeypatch, method, path, payload,
):
    monkeypatch.setattr(traffic_generator, "_loop", AsyncMock())
    try:
        assert client.request(method, path, json=payload).status_code == 200
    finally:
        client.post("/traffic/stop")


def test_empty_control_token_prevents_production_startup(monkeypatch):
    monkeypatch.setattr(Config, "ENVIRONMENT", "production")
    start = Mock()
    monkeypatch.setattr(order_queue, "start", start)

    with pytest.raises(RuntimeError, match="DEMO_SHOP_CONTROL_TOKEN.*production"):
        with TestClient(app):
            pass
    start.assert_not_called()


def test_configured_control_token_allows_production_startup(configured_token, monkeypatch, capsys):
    monkeypatch.setattr(Config, "ENVIRONMENT", "production")
    with TestClient(app) as client:
        assert client.get("/health").status_code == 200
        assert client.get("/faults").status_code == 401
        assert client.get(
            "/faults", headers={"Authorization": f"Bearer {CONTROL_TOKEN}"},
        ).status_code == 200
    assert CONTROL_TOKEN not in capsys.readouterr().out
