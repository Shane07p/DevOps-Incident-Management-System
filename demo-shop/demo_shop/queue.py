"""
In-process bounded order queue for Demo Shop v1.
Simulates asynchronous order fulfillment and backlog scenarios (SCN-04).
"""

from __future__ import annotations

import asyncio
import datetime
import logging
from typing import Any, Dict, Optional

from demo_shop.telemetry import StructuredLogger, telemetry

logger = logging.getLogger("demo_shop.queue")


class OrderQueue:
    """Bounded in-process queue for order fulfillment with controllable processing worker."""

    def __init__(self, maxsize: int = 1000):
        self._queue: asyncio.Queue = asyncio.Queue(maxsize=maxsize)
        self._paused: bool = False
        self._worker_task: Optional[asyncio.Task] = None
        self._running: bool = False
        self._processed_count: int = 0
        self._oldest_enqueue_time: Optional[datetime.datetime] = None

    @property
    def size(self) -> int:
        return self._queue.qsize()

    @property
    def is_paused(self) -> bool:
        from demo_shop.faults import fault_manager
        return self._paused or fault_manager.is_enabled("queue_backlog")

    def pause(self):
        """Pause worker processing to simulate queue worker stall/backlog (SCN-04)."""
        self._paused = True

    def resume(self):
        """Resume worker processing, rapidly draining any accumulated backlog."""
        self._paused = False

    async def enqueue(self, order_data: Dict[str, Any]) -> bool:
        """Enqueue an order for asynchronous fulfillment."""
        now = datetime.datetime.now(datetime.timezone.utc)
        item = {
            "order_id": order_data.get("order_id"),
            "enqueued_at": now,
            "data": order_data,
        }
        try:
            self._queue.put_nowait(item)
            if self._oldest_enqueue_time is None:
                self._oldest_enqueue_time = now

            depth = self._queue.qsize()
            lag = (now - self._oldest_enqueue_time).total_seconds()
            telemetry.update_queue_metrics(depth=depth, lag_seconds=lag)

            StructuredLogger.info(
                service="order-fulfillment",
                event="queue.enqueued",
                details={"order_id": order_data.get("order_id"), "queue_depth": depth},
            )
            return True
        except asyncio.QueueFull:
            StructuredLogger.error(
                service="order-fulfillment",
                event="queue.overflow",
                details={"message": "Order fulfillment queue is full, dropping order"},
            )
            return False

    async def _worker_loop(self):
        """Background worker that continuously drains the queue unless paused."""
        while self._running:
            try:
                depth = self._queue.qsize()
                now = datetime.datetime.now(datetime.timezone.utc)

                if self._paused:
                    # When paused, compute growing lag without processing
                    lag = (now - self._oldest_enqueue_time).total_seconds() if self._oldest_enqueue_time else 0.0
                    telemetry.update_queue_metrics(depth=depth, lag_seconds=lag)

                    if depth > 0:
                        StructuredLogger.warning(
                            service="order-fulfillment",
                            event="queue.backlog_warning",
                            fault_id="SCN-04",
                            details={
                                "queue_depth": depth,
                                "lag_seconds": round(lag, 2),
                                "message": "Worker paused: order backlog growing",
                            },
                        )
                    await asyncio.sleep(0.5)
                else:
                    if depth > 0:
                        item = await self._queue.get()
                        self._processed_count += 1
                        self._queue.task_done()

                        # Update oldest timestamp
                        remaining = self._queue.qsize()
                        if remaining == 0:
                            self._oldest_enqueue_time = None
                            lag = 0.0
                        else:
                            lag = (now - self._oldest_enqueue_time).total_seconds() if self._oldest_enqueue_time else 0.0

                        telemetry.update_queue_metrics(depth=remaining, lag_seconds=lag)

                        StructuredLogger.info(
                            service="order-fulfillment",
                            event="order.fulfilled",
                            details={
                                "order_id": item.get("order_id"),
                                "remaining_depth": remaining,
                            },
                        )
                        # Fast processing delay to drain queue smoothly
                        await asyncio.sleep(0.02)
                    else:
                        self._oldest_enqueue_time = None
                        telemetry.update_queue_metrics(depth=0, lag_seconds=0.0)
                        await asyncio.sleep(0.1)

            except asyncio.CancelledError:
                break
            except Exception as exc:
                logger.error("Error in order fulfillment worker: %s", exc)
                await asyncio.sleep(0.5)

    def start(self):
        """Start the background queue worker."""
        if not self._running:
            self._running = True
            self._worker_task = asyncio.create_task(self._worker_loop())

    async def stop(self):
        """Stop the background worker cleanly."""
        self._running = False
        if self._worker_task:
            self._worker_task.cancel()
            try:
                await self._worker_task
            except asyncio.CancelledError:
                pass


# Global order queue singleton
order_queue = OrderQueue()
