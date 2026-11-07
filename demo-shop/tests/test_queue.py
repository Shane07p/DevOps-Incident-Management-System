"""The fulfillment queue's lag is the wait of the oldest order still waiting (SCN-04)."""

import asyncio

from demo_shop import queue as queue_module
from demo_shop.queue import OrderQueue


def test_lag_after_a_dequeue_is_the_wait_of_the_next_order(monkeypatch):
    reported = []
    monkeypatch.setattr(queue_module.telemetry, "update_queue_metrics",
                        lambda depth, lag_seconds: reported.append((depth, lag_seconds)))

    async def run():
        queue = OrderQueue()
        await queue.enqueue({"order_id": "older"})
        await asyncio.sleep(0.3)
        await queue.enqueue({"order_id": "newer"})
        reported.clear()
        queue.start()
        for _ in range(100):
            if queue._processed_count >= 1:
                break
            await asyncio.sleep(0.01)
        await queue.stop()

    asyncio.run(run())

    # The first report after the older order left: one order waits, and it has only just arrived.
    depth, lag = next(entry for entry in reported if entry[0] == 1)
    assert lag < 0.15
