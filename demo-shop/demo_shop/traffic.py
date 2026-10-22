"""
Configurable and bounded traffic generator for Demo Shop v1.
Exercises search, payments, and checkout with realistic mixtures.
"""

from __future__ import annotations

import argparse
import asyncio
import random
import time
from typing import Optional

import httpx

from demo_shop.models import TrafficStatusResponse
from demo_shop.telemetry import StructuredLogger

SEARCH_TERMS = [
    "headphones",
    "keyboard",
    "monitor",
    "mouse",
    "usb",
    "microphone",
    "cable",
    "audio",
    "wireless",
    "lamp",
]

PRODUCT_IDS = [
    "prod-101",
    "prod-102",
    "prod-103",
    "prod-104",
    "prod-105",
    "prod-106",
    "prod-107",
    "prod-108",
    "prod-109",
    "prod-110",
]


class TrafficGenerator:
    """Bounded, stoppable traffic generator for demo scenarios."""

    def __init__(self):
        self._running: bool = False
        self._task: Optional[asyncio.Task] = None
        self._requests_sent: int = 0
        self._success_count: int = 0
        self._error_count: int = 0
        self._duration: int = 30
        self._start_time: float = 0.0
        self._target_url: str = "http://localhost:8001"
        self._rate: float = 2.0

    @property
    def is_running(self) -> bool:
        return self._running

    def get_status(self) -> TrafficStatusResponse:
        elapsed = time.time() - self._start_time if self._running else 0.0
        remaining = max(0.0, float(self._duration) - elapsed) if self._running else 0.0
        return TrafficStatusResponse(
            running=self._running,
            requests_sent=self._requests_sent,
            success_count=self._success_count,
            error_count=self._error_count,
            duration_seconds=self._duration,
            remaining_seconds=round(remaining, 1),
        )

    async def _send_one_request(self, client: httpx.AsyncClient):
        dice = random.random()
        base = self._target_url.rstrip("/")

        try:
            if dice < 0.60:
                # 60% Search
                query = random.choice(SEARCH_TERMS)
                resp = await client.get(f"{base}/search", params={"q": query})
            elif dice < 0.90:
                # 30% Checkout
                items_count = random.randint(1, 3)
                selected_prods = random.sample(PRODUCT_IDS, items_count)
                items = [
                    {"product_id": pid, "quantity": random.randint(1, 2), "unit_price": 50.0}
                    for pid in selected_prods
                ]
                payload = {
                    "customer_email": f"shopper_{random.randint(100, 999)}@example.com",
                    "items": items,
                    "payment_method": "credit_card",
                }
                resp = await client.post(f"{base}/checkout", json=payload)
            else:
                # 10% Direct Payment
                payload = {
                    "order_id": f"ord-direct-{random.randint(1000, 9999)}",
                    "amount": round(random.uniform(15.0, 300.0), 2),
                    "currency": "USD",
                    "card_token": "tok_visa_4242",
                }
                resp = await client.post(f"{base}/payments/authorize", json=payload)

            self._requests_sent += 1
            if resp.status_code < 400:
                self._success_count += 1
            else:
                self._error_count += 1

        except Exception as exc:
            self._requests_sent += 1
            self._error_count += 1
            StructuredLogger.warning(
                service="traffic-generator",
                event="traffic.request_failed",
                details={"error": str(exc)},
            )

    async def _loop(self, client: Optional[httpx.AsyncClient] = None):
        interval = 1.0 / max(0.1, self._rate)
        end_time = self._start_time + self._duration

        if client is not None:
            while self._running and time.time() < end_time:
                await self._send_one_request(client)
                await asyncio.sleep(interval)
        else:
            timeout = httpx.Timeout(2.0, connect=0.5)
            async with httpx.AsyncClient(timeout=timeout) as c:
                while self._running and time.time() < end_time:
                    await self._send_one_request(c)
                    await asyncio.sleep(interval)

        self._running = False
        StructuredLogger.info(
            service="traffic-generator",
            event="traffic.finished",
            details={
                "requests_sent": self._requests_sent,
                "success_count": self._success_count,
                "error_count": self._error_count,
            },
        )

    def start(
        self,
        rate: float = 2.0,
        duration_seconds: int = 30,
        target_url: str = "http://localhost:8001",
        client: Optional[httpx.AsyncClient] = None,
    ):
        """Start the traffic generator bounded in duration."""
        if self._running:
            return

        self._running = True
        self._rate = max(0.1, min(rate, 50.0))
        self._duration = max(1, min(duration_seconds, 300))
        self._target_url = target_url
        self._requests_sent = 0
        self._success_count = 0
        self._error_count = 0
        self._start_time = time.time()

        StructuredLogger.info(
            service="traffic-generator",
            event="traffic.started",
            details={
                "rate": self._rate,
                "duration_seconds": self._duration,
                "target_url": self._target_url,
            },
        )
        try:
            loop = asyncio.get_running_loop()
            self._task = loop.create_task(self._loop(client=client))
        except RuntimeError:
            self._task = None

    async def stop(self):
        """Cleanly stop the traffic generator."""
        self._running = False
        if self._task and not self._task.done():
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass


traffic_generator = TrafficGenerator()


async def run_cli():
    parser = argparse.ArgumentParser(description="Demo Shop Traffic Generator")
    parser.add_argument("--rate", type=float, default=2.0, help="Requests per second")
    parser.add_argument("--duration", type=int, default=30, help="Duration in seconds (bounded)")
    parser.add_argument("--url", type=str, default="http://localhost:8001", help="Target demo-shop URL")
    args = parser.parse_args()

    print(f"Starting traffic generator against {args.url} (rate: {args.rate}/s, duration: {args.duration}s)...")
    traffic_generator.start(rate=args.rate, duration_seconds=args.duration, target_url=args.url)
    while traffic_generator.is_running:
        status = traffic_generator.get_status()
        print(f"Progress: {status.requests_sent} requests sent ({status.success_count} ok, {status.error_count} err) - {status.remaining_seconds}s left", end="\r")
        await asyncio.sleep(1.0)
    print("\nTraffic run completed.")


if __name__ == "__main__":
    asyncio.run(run_cli())
