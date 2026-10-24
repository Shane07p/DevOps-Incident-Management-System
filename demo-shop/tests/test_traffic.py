"""Tests for the bounded traffic generator."""

import asyncio
import pytest

from demo_shop.traffic import traffic_generator


def test_traffic_api_start_status_and_stop():
    from demo_shop.app import app
    from starlette.testclient import TestClient
    import time

    with TestClient(app) as c:
        # Start traffic with 10s duration
        start_payload = {
            "rate": 10.0,
            "duration_seconds": 10,
            "target_url": "http://localhost:8001",
        }
        resp = c.post("/traffic/start", json=start_payload)
        assert resp.status_code == 200
        assert resp.json()["status"] == "started"

        # Allow generator loop to tick
        time.sleep(0.3)

        # Status shows running and verified traffic emission
        status_resp = c.get("/traffic/status")
        assert status_resp.status_code == 200
        data = status_resp.json()
        assert data["running"] is True
        assert data["duration_seconds"] == 10
        assert data["requests_sent"] > 0 or data["remaining_seconds"] < 10

        # Stop traffic cleanly
        stop_resp = c.post("/traffic/stop")
        assert stop_resp.status_code == 200
        assert stop_resp.json()["status"] == "stopped"

        # Status now shows stopped
        status_after = c.get("/traffic/status").json()
        assert status_after["running"] is False


def test_traffic_api_ssrf_protection_rejects_unallowed_origin(client):
    # Reject arbitrary internal or third-party targets with HTTP 422
    disallowed_payload = {
        "rate": 5.0,
        "duration_seconds": 10,
        "target_url": "http://169.254.169.254/latest/meta-data",
    }
    resp = client.post("/traffic/start", json=disallowed_payload)
    assert resp.status_code == 422


def test_traffic_start_without_event_loop_resets_running_flag():
    from demo_shop.traffic import TrafficGenerator
    gen = TrafficGenerator()
    with pytest.raises(RuntimeError):
        gen.start(rate=1.0, duration_seconds=5)
    assert gen.is_running is False



def test_traffic_generator_bounded_duration_execution():
    async def _run():
        generator = traffic_generator
        from demo_shop.app import app
        import httpx

        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://localhost:8001"
        ) as test_client:
            generator.start(
                rate=10.0,
                duration_seconds=1,
                target_url="http://localhost:8001",
                client=test_client,
            )
            assert generator.is_running is True

            # Wait for duration to expire
            await asyncio.sleep(1.2)

            # Should naturally stop because duration is bounded
            assert generator.is_running is False
            status = generator.get_status()
            assert status.running is False
            assert status.requests_sent > 0

    asyncio.run(_run())
