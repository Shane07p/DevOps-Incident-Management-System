"""
Checkout service component for Demo Shop v1.
Coordinates order checkout, calls payment service, enqueues to fulfillment,
and handles fault injection (SCN-02 error_spike, SCN-03 bad_deploy).
"""

from __future__ import annotations

import hashlib
import random
import uuid
from typing import Optional

from fastapi import HTTPException

from demo_shop.faults import fault_manager
from demo_shop.models import CheckoutRequest, CheckoutResponse, PaymentRequest
from demo_shop.queue import order_queue
from demo_shop.services.payments import payment_service
from demo_shop.services.search import search_service
from demo_shop.telemetry import StructuredLogger, telemetry


class CheckoutService:
    """Coordinates checkout flow across search validation, payments, and order queue."""

    async def process_checkout(
        self,
        request: CheckoutRequest,
        request_id: Optional[str] = None,
        force_error_spike: bool = False,
    ) -> CheckoutResponse:
        current_version = fault_manager.get_version()

        # Check Scenario SCN-03: Bad Deploy
        if fault_manager.is_enabled("bad_deploy"):
            error_msg = f"NullPointerException in CheckoutPayloadValidator: cart_hash was null (version: {current_version})"
            StructuredLogger.error(
                service="checkout",
                event="checkout.null_pointer_exception",
                request_id=request_id,
                fault_id="SCN-03",
                details={
                    "customer_hash": hashlib.sha256(request.customer_email.encode()).hexdigest()[:12],
                    "version": current_version,
                    "error": error_msg,
                },
            )
            raise HTTPException(
                status_code=500,
                detail=f"Internal Server Error: {error_msg}",
            )

        # Check Scenario SCN-02: Error Spike (~80% failure rate when active, or 100% when forced in test)
        if fault_manager.is_enabled("error_spike"):
            should_fail = force_error_spike or (random.random() < 0.8)
            if should_fail:
                error_msg = "Database deadlock: concurrent update on orders table in checkout transaction"
                StructuredLogger.error(
                    service="checkout",
                    event="checkout.transaction_failed",
                    request_id=request_id,
                    fault_id="SCN-02",
                    details={
                        "customer_hash": hashlib.sha256(request.customer_email.encode()).hexdigest()[:12],
                        "version": current_version,
                        "error": error_msg,
                    },
                )
                raise HTTPException(
                    status_code=500,
                    detail=f"Internal Server Error: {error_msg}",
                )

        # Calculate total price and validate products
        total_amount = 0.0
        for item in request.items:
            # Use price from catalog if product exists, or unit_price
            catalog_item = search_service.get_by_id(item.product_id)
            price = catalog_item.price if catalog_item else item.unit_price
            total_amount += price * item.quantity

        order_id = f"ord-{uuid.uuid4().hex[:10]}"

        # Authorize payment with payment service
        payment_req = PaymentRequest(
            order_id=order_id,
            amount=round(total_amount, 2),
            currency="USD",
            card_token=f"tok_{request.payment_method}_demo",
        )

        payment_resp = await payment_service.authorize(payment_req, request_id=request_id)

        # Enqueue order into asynchronous fulfillment queue (SCN-04)
        order_payload = {
            "order_id": order_id,
            "customer_email": request.customer_email,
            "items_count": len(request.items),
            "total_amount": round(total_amount, 2),
            "payment_id": payment_resp.transaction_id,
            "version": current_version,
        }
        await order_queue.enqueue(order_payload)

        StructuredLogger.info(
            service="checkout",
            event="checkout.completed",
            request_id=request_id,
            details={
                "order_id": order_id,
                "customer_hash": hashlib.sha256(request.customer_email.encode()).hexdigest()[:12],
                "amount": round(total_amount, 2),
                "items": len(request.items),
                "version": current_version,
            },
        )

        return CheckoutResponse(
            order_id=order_id,
            status="completed",
            total_amount=round(total_amount, 2),
            payment=payment_resp,
            version=current_version,
        )


checkout_service = CheckoutService()
