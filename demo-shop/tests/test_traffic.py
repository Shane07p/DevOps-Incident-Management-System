"""Tests for the bounded traffic generator."""

import asyncio
import pytest

from demo_shop.traffic import traffic_generator


def test_traffic_api_start_status_and_stop(client):
    # Start traffic with 10s duration
    start_payload = {
        "rate": 5.0,
        "duration_seconds": 10,
        "target_url": "http://localhost:8001",
    }
    resp = client.post("/traffic/start", json=start_payload)
    assert resp.status_code == 200
    assert resp.json()["status"] == "started"

    # Status shows running
    status_resp = client.get("/traffic/status")
    assert status_resp.status_code == 200
    data = status_resp.json()
    assert data["running"] is True
    assert data["duration_seconds"] == 10

    # Stop traffic cleanly
    stop_resp = client.post("/traffic/stop")
    assert stop_resp.status_code == 200
    assert stop_resp.json()["status"] == "stopped"

    # Status now shows stopped
    status_after = client.get("/traffic/status").json()
    assert status_after["running"] is False


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
