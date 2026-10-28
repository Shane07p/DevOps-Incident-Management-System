"""
Payments service component for Demo Shop v1.
Simulates payment authorization and gateway interactions with fault switches (SCN-01, SCN-05).
"""

from __future__ import annotations

import asyncio
import time
import uuid
from typing import Optional

from demo_shop.faults import fault_manager
from demo_shop.models import PaymentRequest, PaymentResponse
from demo_shop.telemetry import StructuredLogger, telemetry


class PaymentService:
    """Simulates payment authorization gateway with controllable latency and SSL faults."""

    def __init__(self, slow_gateway_delay: float = 2.0):
        self.slow_gateway_delay = slow_gateway_delay

    async def authorize(
        self,
        request: PaymentRequest,
        request_id: Optional[str] = None,
        override_delay: Optional[float] = None,
    ) -> PaymentResponse:
        start_time = time.perf_counter()

        # Check Scenario SCN-05: Expiring Certificate
        if fault_manager.is_enabled("expiring_certificate"):
            telemetry.ssl_errors_total.add(1, {"host": "gateway.payments.internal"})
            StructuredLogger.error(
                service="payments",
                event="ssl.handshake_warning",
                request_id=request_id,
                fault_id="SCN-05",
                details={
                    "host": "gateway.payments.internal",
                    "cert_days_remaining": 0,
                    "error": "TLS certificate expired or invalid for gateway.payments.internal",
                },
            )

        # Check Scenario SCN-01: Slow Gateway
        if fault_manager.is_enabled("slow_gateway"):
            delay = override_delay if override_delay is not None else self.slow_gateway_delay
            StructuredLogger.warning(
                service="payments",
                event="payment.gateway.slow",
                request_id=request_id,
                fault_id="SCN-01",
                details={
                    "order_id": request.order_id,
                    "injected_delay_seconds": delay,
                    "message": "Payment gateway latency exceeds SLA threshold (>1.5s)",
                },
            )
            await asyncio.sleep(delay)

        latency_ms = (time.perf_counter() - start_time) * 1000.0
        tx_id = f"tx-{uuid.uuid4().hex[:12]}"

        # Record metric duration
        telemetry.http_request_duration_seconds.record(
            latency_ms / 1000.0,
            {"service": "payments", "endpoint": "/payments/authorize"},
        )

        StructuredLogger.info(
            service="payments",
            event="payment.authorized",
            request_id=request_id,
            details={
                "order_id": request.order_id,
                "transaction_id": tx_id,
                "amount": request.amount,
                "currency": request.currency,
                "latency_ms": round(latency_ms, 2),
            },
        )

        return PaymentResponse(
            transaction_id=tx_id,
            order_id=request.order_id,
            status="authorized",
            amount=request.amount,
            currency=request.currency,
            latency_ms=round(latency_ms, 2),
            message="Payment successfully authorized",
        )


payment_service = PaymentService()
