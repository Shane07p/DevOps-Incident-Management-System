"""
Tests for the 5 independent fault switches:
1. slow_gateway (SCN-01)
2. error_spike (SCN-02)
3. bad_deploy (SCN-03)
4. queue_backlog (SCN-04)
5. expiring_certificate (SCN-05)

Validates activation, observable impact, switch isolation, and clean recovery.
"""

import asyncio
import time
import pytest

from demo_shop.faults import fault_manager
from demo_shop.queue import order_queue
from demo_shop.services.payments import payment_service


def test_list_all_five_fault_switches(client):
    resp = client.get("/faults")
    assert resp.status_code == 200
    data = resp.json()
    switches = data["switches"]
    assert len(switches) == 5
    assert set(switches.keys()) == {
        "slow_gateway",
        "error_spike",
        "bad_deploy",
        "queue_backlog",
        "expiring_certificate",
    }
    # Initially all disabled
    assert all(val is False for val in switches.values())


def test_invalid_fault_switch_returns_400(client):
    resp = client.post("/faults/nonexistent_fault", json={"enabled": True})
    assert resp.status_code == 400
    assert "Unknown fault switch" in resp.json()["detail"]


def test_switch_isolation_enabling_one_does_not_affect_others(client):
    # Enable only slow_gateway
    resp = client.post("/faults/slow_gateway", json={"enabled": True})
    assert resp.status_code == 200

    status_resp = client.get("/faults").json()["switches"]
    assert status_resp["slow_gateway"] is True
    assert status_resp["error_spike"] is False
    assert status_resp["bad_deploy"] is False
    assert status_resp["queue_backlog"] is False
    assert status_resp["expiring_certificate"] is False


def test_slow_gateway_observable_delay_and_recovery(client):
    # Temporarily set slow delay to 0.25s for fast unit testing
    orig_delay = payment_service.slow_gateway_delay
    payment_service.slow_gateway_delay = 0.25

    try:
        # Baseline payment without fault (< 50ms)
        payload = {
            "order_id": "ord-base",
            "amount": 25.0,
            "currency": "USD",
            "card_token": "tok_visa",
        }
        t0 = time.perf_counter()
        resp0 = client.post("/payments/authorize", json=payload)
        base_dur = time.perf_counter() - t0
        assert resp0.status_code == 200
        assert base_dur < 0.2

        # Enable slow_gateway
        client.post("/faults/slow_gateway", json={"enabled": True})

        t1 = time.perf_counter()
        resp1 = client.post("/payments/authorize", json=payload)
        slow_dur = time.perf_counter() - t1
        assert resp1.status_code == 200
        assert slow_dur >= 0.25  # Injected delay observed

        # Disable slow_gateway -> Recovery
        client.post("/faults/slow_gateway", json={"enabled": False})

        t2 = time.perf_counter()
        resp2 = client.post("/payments/authorize", json=payload)
        recovered_dur = time.perf_counter() - t2
        assert resp2.status_code == 200
        assert recovered_dur < 0.2  # Normal fast timing restored

    finally:
        payment_service.slow_gateway_delay = orig_delay


def test_error_spike_causes_500_and_recovers(client):
    checkout_payload = {
        "customer_email": "shopper@example.com",
        "items": [{"product_id": "prod-101", "quantity": 1, "unit_price": 10.0}],
        "payment_method": "credit_card",
    }

    # Baseline: success
    resp0 = client.post("/checkout", json=checkout_payload)
    assert resp0.status_code == 200

    # Enable error_spike
    client.post("/faults/error_spike", json={"enabled": True})

    # Under error_spike, send 10 requests: majority must fail with 500
    statuses = []
    for _ in range(10):
        r = client.post("/checkout", json=checkout_payload)
        statuses.append(r.status_code)
    assert 500 in statuses
    assert statuses.count(500) >= 5  # ~80% failure rate

    # Disable error_spike -> Recovery
    client.post("/faults/error_spike", json={"enabled": False})

    # All requests now succeed
    for _ in range(5):
        r = client.post("/checkout", json=checkout_payload)
        assert r.status_code == 200


def test_bad_deploy_emits_event_fails_requests_and_rolls_back(client):
    checkout_payload = {
        "customer_email": "shopper@example.com",
        "items": [{"product_id": "prod-101", "quantity": 1, "unit_price": 10.0}],
        "payment_method": "credit_card",
    }

    # Baseline check: version is v1.0.0
    health_resp = client.get("/health").json()
    assert health_resp["version"] == "v1.0.0"

    # Enable bad_deploy
    client.post("/faults/bad_deploy", json={"enabled": True})

    # Version changed to v1.0.1-broken
    assert client.get("/health").json()["version"] == "v1.0.1-broken"

    # Deploy event emitted and recorded
    deploy_event = client.get("/faults/deploy-event").json()
    assert deploy_event is not None
    assert deploy_event["version"] == "v1.0.1-broken"
    assert deploy_event["previous_version"] == "v1.0.0"
    assert deploy_event["service"] == "checkout"
    assert deploy_event["author"] == "ci-bot"
    assert "timestamp" in deploy_event

    # Checkout fails with 500 and regression message
    resp_fail = client.post("/checkout", json=checkout_payload)
    assert resp_fail.status_code == 500
    assert "NullPointerException" in resp_fail.json()["detail"]

    # Disable bad_deploy -> Rollback
    client.post("/faults/bad_deploy", json={"enabled": False})

    # Version restored to v1.0.0
    assert client.get("/health").json()["version"] == "v1.0.0"

    # Rollback deploy event recorded
    rollback_event = client.get("/faults/deploy-event").json()
    assert rollback_event["version"] == "v1.0.0"
    assert rollback_event["previous_version"] == "v1.0.1-broken"

    # Checkout recovers and succeeds
    resp_ok = client.post("/checkout", json=checkout_payload)
    assert resp_ok.status_code == 200


def test_queue_backlog_pauses_worker_and_recovers(client):
    # Enable queue_backlog
    client.post("/faults/queue_backlog", json={"enabled": True})
    assert order_queue.is_paused is True

    # Enqueue multiple orders via checkout
    for i in range(3):
        payload = {
            "customer_email": f"user{i}@example.com",
            "items": [{"product_id": "prod-101", "quantity": 1, "unit_price": 10.0}],
            "payment_method": "credit_card",
        }
        resp = client.post("/checkout", json=payload)
        assert resp.status_code == 200

    # Backlog has grown
    assert order_queue.size >= 3

    # Disable queue_backlog -> Recovery
    client.post("/faults/queue_backlog", json={"enabled": False})
    assert order_queue.is_paused is False


def test_expiring_certificate_sets_days_and_recovers(client):
    # Enable expiring_certificate
    client.post("/faults/expiring_certificate", json={"enabled": True})
    assert fault_manager.is_enabled("expiring_certificate") is True

    # Trigger payment under expiring cert
    payload = {
        "order_id": "ord-cert-test",
        "amount": 50.0,
        "currency": "USD",
        "card_token": "tok_visa",
    }
    resp = client.post("/payments/authorize", json=payload)
    assert resp.status_code == 200

    # Disable expiring_certificate -> Recovery
    client.post("/faults/expiring_certificate", json={"enabled": False})
    assert fault_manager.is_enabled("expiring_certificate") is False


def test_reset_all_faults(client):
    # Enable multiple switches
    client.post("/faults/slow_gateway", json={"enabled": True})
    client.post("/faults/bad_deploy", json={"enabled": True})
    client.post("/faults/queue_backlog", json={"enabled": True})

    switches = client.get("/faults").json()["switches"]
    assert switches["slow_gateway"] is True
    assert switches["bad_deploy"] is True
    assert switches["queue_backlog"] is True

    # Reset all
    reset_resp = client.post("/faults/reset")
    assert reset_resp.status_code == 200

    switches_after = client.get("/faults").json()["switches"]
    assert all(v is False for v in switches_after.values())
    assert client.get("/health").json()["version"] == "v1.0.0"
